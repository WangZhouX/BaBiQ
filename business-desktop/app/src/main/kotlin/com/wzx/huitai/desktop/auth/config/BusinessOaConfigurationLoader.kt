package com.wzx.huitai.desktop.auth.config

import com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.Properties

/**
 * 按“显式文件、用户文件、内置默认”的优先级选择一份完整配置。
 *
 * 各层不会逐键合并。没有用户文件时，内置默认先通过同目录临时文件原子安装，之后再按普通
 * 用户文件读取，使安装包配置只承担安全初始值的职责。
 */
class BusinessOaConfigurationLoader(
    private val environment: Map<String, String> = System.getenv(),
    private val bundledDefault: () -> InputStream? = {
        BusinessOaConfigurationLoader::class.java
            .getResourceAsStream(BUNDLED_CONFIGURATION_RESOURCE)
    },
) {
    fun load(paths: BusinessDesktopRuntimePaths): BusinessOaConfiguration {
        val explicitValue = environment[EXPLICIT_CONFIGURATION_ENV]?.trim().orEmpty()
        val selected = if (explicitValue.isNotEmpty()) {
            explicitConfigurationPath(explicitValue)
        } else {
            ensureUserConfiguration(paths.desktopConfiguration)
            paths.desktopConfiguration
        }
        return readAndValidate(selected)
    }

    override fun toString(): String = "BusinessOaConfigurationLoader(source=[REDACTED])"

    private fun explicitConfigurationPath(rawValue: String): Path {
        val rawPath = runCatching { Path.of(rawValue) }
            .getOrElse { invalid() }
        if (!rawPath.isAbsolute) invalid()
        val path = rawPath.normalize()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) unavailable()
        if (!isOrdinaryFile(path)) invalid()
        return path
    }

    private fun ensureUserConfiguration(path: Path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!isOrdinaryFile(path)) invalid()
            return
        }
        try {
            Files.createDirectories(path.parent)
            val temporary = Files.createTempFile(path.parent, "business-desktop-", ".tmp")
            try {
                bundledDefault()?.use { input ->
                    Files.newOutputStream(temporary).use { output -> input.copyTo(output) }
                } ?: unavailable()
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (_: BusinessOaConfigurationException) {
            throw BusinessOaConfigurationException(BusinessOaConfigurationErrorCode.CONFIG_UNAVAILABLE)
        } catch (_: Exception) {
            unavailable()
        }
        if (!isOrdinaryFile(path)) unavailable()
    }

    private fun readAndValidate(path: Path): BusinessOaConfiguration {
        if (!isOrdinaryFile(path)) invalid()
        val properties = Properties()
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(properties::load)
        } catch (_: Exception) {
            unavailable()
        }
        rejectSensitiveKeys(properties)

        val allowInsecureHttp = required(properties, ALLOW_INSECURE_HTTP).let { value ->
            when (value.lowercase(Locale.ROOT)) {
                "true" -> true
                "false" -> false
                else -> invalid()
            }
        }
        val baseUrl = validateUrl(required(properties, BASE_URL), allowInsecureHttp)
        val apiPrefix = validateApiPrefix(required(properties, API_PREFIX))
        val platformId = required(properties, PLATFORM_ID).toIntOrNull()
            ?.takeIf { it > 0 }
            ?: invalid()
        val timeout = required(properties, REQUEST_TIMEOUT_MS).toLongOrNull()
            ?.takeIf { it in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS }
            ?: invalid()
        val serviceAgreementUrl = validateUrl(required(properties, SERVICE_AGREEMENT_URL), allowInsecureHttp)
        val privacyPolicyUrl = validateUrl(required(properties, PRIVACY_POLICY_URL), allowInsecureHttp)

        return BusinessOaConfiguration(
            baseUrl = baseUrl,
            apiPrefix = apiPrefix,
            platformId = platformId,
            requestTimeoutMs = timeout,
            serviceAgreementUrl = serviceAgreementUrl,
            privacyPolicyUrl = privacyPolicyUrl,
            allowInsecureHttp = allowInsecureHttp,
        )
    }

    private fun rejectSensitiveKeys(properties: Properties) {
        properties.stringPropertyNames().forEach { key ->
            val normalized = key.lowercase(Locale.ROOT).replace('_', '-')
            if (SENSITIVE_KEY_MARKERS.any(normalized::contains)) invalid()
        }
    }

    private fun required(properties: Properties, key: String): String =
        properties.getProperty(key)?.trim()?.takeIf(String::isNotEmpty) ?: invalid()

    private fun validateUrl(rawValue: String, allowInsecureHttp: Boolean): String {
        val uri = runCatching { URI(rawValue) }.getOrElse { invalid() }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (!uri.isAbsolute || uri.isOpaque || host.isNullOrBlank()) invalid()
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) invalid()
        if (uri.port != -1 && uri.port !in 1..65535) invalid()
        when (scheme) {
            "https" -> Unit
            "http" -> if (!allowInsecureHttp || !isLoopbackOrPrivateIpv4(host)) invalid()
            else -> invalid()
        }
        return rawValue
    }

    private fun validateApiPrefix(rawValue: String): String {
        if (!API_PREFIX_PATTERN.matches(rawValue)) invalid()
        if (rawValue.split('/').any { it == "." || it == ".." }) invalid()
        return rawValue
    }

    private fun isLoopbackOrPrivateIpv4(host: String): Boolean {
        if (host == "localhost") return true
        val octets = host.split('.').takeIf { it.size == 4 }
            ?.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: return false }
            ?: return host == "[::1]" || host == "::1"
        return octets[0] == 127 ||
            octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private fun isOrdinaryFile(path: Path): Boolean = runCatching {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        attributes.isRegularFile && !attributes.isOther && !Files.isSymbolicLink(path)
    }.getOrDefault(false)

    private fun invalid(): Nothing =
        throw BusinessOaConfigurationException(BusinessOaConfigurationErrorCode.CONFIG_INVALID)

    private fun unavailable(): Nothing =
        throw BusinessOaConfigurationException(BusinessOaConfigurationErrorCode.CONFIG_UNAVAILABLE)

    private companion object {
        const val EXPLICIT_CONFIGURATION_ENV = "HUITAI_DESKTOP_CONFIG_FILE"
        const val BUNDLED_CONFIGURATION_RESOURCE = "/config/business-desktop.properties"
        const val BASE_URL = "huitai.oa.base-url"
        const val API_PREFIX = "huitai.oa.api-prefix"
        const val PLATFORM_ID = "huitai.oa.platform-id"
        const val REQUEST_TIMEOUT_MS = "huitai.oa.request-timeout-ms"
        const val SERVICE_AGREEMENT_URL = "huitai.oa.service-agreement-url"
        const val PRIVACY_POLICY_URL = "huitai.oa.privacy-policy-url"
        const val ALLOW_INSECURE_HTTP = "huitai.oa.allow-insecure-http"
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 120_000L

        val API_PREFIX_PATTERN = Regex("/(?:[A-Za-z0-9][A-Za-z0-9._~-]*)(?:/[A-Za-z0-9][A-Za-z0-9._~-]*)*")
        val SENSITIVE_KEY_MARKERS = listOf("password", "token", "secret", "api-key", "apikey")
    }
}
