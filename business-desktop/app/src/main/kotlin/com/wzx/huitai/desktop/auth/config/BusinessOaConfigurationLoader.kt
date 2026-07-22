package com.wzx.huitai.desktop.auth.config

import com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * 按“显式文件、用户文件、内置默认”的优先级选择一份完整配置。
 *
 * 各层不会逐键合并。没有用户文件时，内置默认先通过同目录临时文件原子安装，之后再按普通
 * 用户文件读取，使安装包配置只承担安全初始值的职责。
 */
open class BusinessOaConfigurationLoader(
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
            requireOrdinaryFile(path)
            return
        }
        val normalizedPath = path.toAbsolutePath().normalize()
        val mutex = BOOTSTRAP_MUTEXES.computeIfAbsent(normalizedPath) { Any() }
        synchronized(mutex) {
            ensureUserConfigurationUnderJvmLock(normalizedPath)
        }
    }

    /**
     * JVM 锁避免同一进程触发重叠 FileLock；相邻锁文件则把同一路径的初始化串行化到跨进程范围。
     * 取得锁后必须重新检查目标，后到者只读取获胜文件，绝不再次安装内置默认值。
     */
    private fun ensureUserConfigurationUnderJvmLock(path: Path) {
        try {
            Files.createDirectories(path.parent)
        } catch (_: Exception) {
            unavailable()
        }
        val lockPath = path.resolveSibling("${path.fileName}.bootstrap.lock")
        val lockChannel = try {
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: Exception) {
            unavailable()
        }
        lockChannel.use { channel ->
            val lock = try {
                channel.lock()
            } catch (_: Exception) {
                unavailable()
            }
            lock.use {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    requireOrdinaryFile(path)
                    return
                }
                bootstrapUserConfiguration(path)
            }
        }
    }

    private fun bootstrapUserConfiguration(path: Path) {
        val temporary = try {
            Files.createTempFile(path.parent, "business-desktop-", ".tmp")
        } catch (_: Exception) {
            unavailable()
        }
        try {
            val source = try {
                bundledDefault()
            } catch (_: Exception) {
                unavailable()
            } ?: unavailable()
            try {
                source.use { writeTemporaryConfiguration(temporary, it) }
            } catch (error: BusinessOaConfigurationException) {
                throw error
            } catch (_: Exception) {
                unavailable()
            }

            // 覆盖“首次检查之后用户文件出现”的窗口；获胜文件必须继续接受普通文件/link 校验。
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                requireOrdinaryFile(path)
                return
            }
            val installed = try {
                moveTemporaryConfigurationIfAbsent(temporary, path)
            } catch (error: BusinessOaConfigurationException) {
                throw error
            } catch (_: Exception) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    requireOrdinaryFile(path)
                    return
                }
                unavailable()
            }
            if (!installed) {
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) unavailable()
                requireOrdinaryFile(path)
                return
            }
            forceDirectoryBestEffort(path.parent)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    /** 写完临时文件后强制刷新文件内容和元数据，供失败注入测试覆盖。 */
    internal open fun writeTemporaryConfiguration(temporary: Path, source: InputStream) {
        FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(bytes)
                if (count < 0) break
                if (count == 0) continue
                val buffer = ByteBuffer.wrap(bytes, 0, count)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            channel.force(true)
        }
    }

    /**
     * 锁内仍采用 create-if-absent 语义：移动前再次观察目标，原子移动遇到已存在目标时由调用方读取获胜文件。
     */
    internal open fun moveTemporaryConfigurationIfAbsent(temporary: Path, target: Path): Boolean {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: FileAlreadyExistsException) {
            false
        }
    }

    /** 目录句柄刷新在 Windows 等不支持目录 channel 的平台会失败，因此按协议 best-effort。 */
    private fun forceDirectoryBestEffort(directory: Path) {
        runCatching {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
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

    private fun requireOrdinaryFile(path: Path) {
        if (!isOrdinaryFile(path)) invalid()
    }

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
        val BOOTSTRAP_MUTEXES = ConcurrentHashMap<Path, Any>()
    }
}
