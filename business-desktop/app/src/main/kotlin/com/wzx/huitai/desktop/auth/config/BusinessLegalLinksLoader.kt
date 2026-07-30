package com.wzx.huitai.desktop.auth.config

import com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.Properties

/**
 * 按“显式文件、用户文件、内置默认”的优先级选择一份完整配置。
 *
 * 各层不会逐键合并。没有用户文件时，内置默认先通过同目录临时文件原子安装，之后再按普通
 * 用户文件读取，使安装包配置只承担安全初始值的职责。
 */
class BusinessLegalLinksLoader internal constructor(
    private val environment: Map<String, String> = System.getenv(),
    private val bundledDefault: () -> InputStream?,
    private val bootstrapInstaller: BusinessLegalLinksBootstrapInstaller,
) {
    constructor(
        environment: Map<String, String> = System.getenv(),
        bundledDefault: () -> InputStream? = {
            BusinessLegalLinksLoader::class.java
                .getResourceAsStream(BUNDLED_CONFIGURATION_RESOURCE)
        },
    ) : this(environment, bundledDefault, AtomicBusinessLegalLinksBootstrap())

    fun load(paths: BusinessDesktopRuntimePaths): BusinessLegalLinksConfiguration {
        val explicitValue = environment[EXPLICIT_CONFIGURATION_ENV]?.trim().orEmpty()
        val selected = if (explicitValue.isNotEmpty()) {
            explicitConfigurationPath(explicitValue)
        } else {
            bootstrapInstaller.installIfAbsent(paths.desktopConfiguration, bundledDefault)
            paths.desktopConfiguration
        }
        return readAndValidate(selected)
    }

    override fun toString(): String = "BusinessLegalLinksLoader(source=[REDACTED])"

    private fun explicitConfigurationPath(rawValue: String): Path {
        val rawPath = runCatching { Path.of(rawValue) }
            .getOrElse { invalid() }
        if (!rawPath.isAbsolute) invalid()
        val path = rawPath.normalize()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) unavailable()
        if (!isOrdinaryFile(path)) invalid()
        return path
    }

    private fun readAndValidate(path: Path): BusinessLegalLinksConfiguration {
        if (!isOrdinaryFile(path)) invalid()
        val properties = Properties()
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(properties::load)
        } catch (_: Exception) {
            unavailable()
        }
        if (properties.stringPropertyNames() != ALLOWED_CONFIGURATION_KEYS) invalid()

        val serviceAgreementUrl = validateHttpsUrl(required(properties, SERVICE_AGREEMENT_URL))
        val privacyPolicyUrl = validateHttpsUrl(required(properties, PRIVACY_POLICY_URL))

        return BusinessLegalLinksConfiguration(
            serviceAgreementUrl = serviceAgreementUrl,
            privacyPolicyUrl = privacyPolicyUrl,
        )
    }

    private fun required(properties: Properties, key: String): String =
        properties.getProperty(key)?.trim()?.takeIf(String::isNotEmpty) ?: invalid()

    private fun validateHttpsUrl(rawValue: String): String {
        val uri = runCatching { URI(rawValue) }.getOrElse { invalid() }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (!uri.isAbsolute || uri.isOpaque || host.isNullOrBlank()) invalid()
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) invalid()
        if (uri.port != -1 && uri.port !in 1..65535) invalid()
        if (scheme != "https") invalid()
        return rawValue
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
        throw BusinessLegalLinksConfigurationException(BusinessLegalLinksConfigurationErrorCode.CONFIG_INVALID)

    private fun unavailable(): Nothing =
        throw BusinessLegalLinksConfigurationException(BusinessLegalLinksConfigurationErrorCode.CONFIG_UNAVAILABLE)

    private companion object {
        const val EXPLICIT_CONFIGURATION_ENV = "HUITAI_DESKTOP_CONFIG_FILE"
        const val BUNDLED_CONFIGURATION_RESOURCE = "/config/business-desktop.properties"
        const val SERVICE_AGREEMENT_URL = "business.legal.service-agreement-url"
        const val PRIVACY_POLICY_URL = "business.legal.privacy-policy-url"
        val ALLOWED_CONFIGURATION_KEYS = setOf(SERVICE_AGREEMENT_URL, PRIVACY_POLICY_URL)
    }
}
