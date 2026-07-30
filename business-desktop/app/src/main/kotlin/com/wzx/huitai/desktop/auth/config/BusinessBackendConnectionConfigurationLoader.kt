package com.wzx.huitai.desktop.auth.config

import com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties

/** 从业务桌面 properties 读取并校验独立后端连接地址。 */
class BusinessBackendConnectionConfigurationLoader(
    private val environment: Map<String, String> = System.getenv(),
) {
    fun load(paths: BusinessDesktopRuntimePaths): BusinessBackendConnectionConfiguration {
        val configuredPath = environment[EXPLICIT_CONFIGURATION_ENV]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { Path.of(it) }
            ?.also { require(it.isAbsolute) { "desktop configuration path must be absolute" } }
            ?: paths.desktopConfiguration
        val path = configuredPath.toAbsolutePath().normalize()
        require(isOrdinaryFile(path)) { "desktop backend configuration is unavailable" }
        val properties = Properties()
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(properties::load)
        val websocketUrl = required(properties, WEBSOCKET_URL)
        val localOrigin = required(properties, LOCAL_ORIGIN)
        validate(websocketUrl, localOrigin)
        return BusinessBackendConnectionConfiguration(websocketUrl, localOrigin)
    }

    private fun required(properties: Properties, key: String): String =
        properties.getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("desktop backend configuration is invalid")

    private fun validate(websocketUrl: String, localOrigin: String) {
        val websocket = runCatching { URI(websocketUrl) }
            .getOrElse { throw IllegalArgumentException("desktop backend WebSocket URL is invalid", it) }
        require(websocket.scheme.equals("ws", ignoreCase = true)) {
            "desktop backend must use ws"
        }
        require(websocket.host == "127.0.0.1" || websocket.host == "localhost") {
            "desktop backend must target loopback"
        }
        require(websocket.port in 1..65_535 && websocket.path == "/ws/agent") {
            "desktop backend WebSocket endpoint is invalid"
        }
        require(websocket.userInfo == null && websocket.query == null && websocket.fragment == null) {
            "desktop backend WebSocket URL contains unsupported components"
        }
        val origin = runCatching { URI(localOrigin) }
            .getOrElse { throw IllegalArgumentException("desktop backend local Origin is invalid", it) }
        require(origin.scheme.equals("http", ignoreCase = true))
        require(origin.host == websocket.host && origin.port == websocket.port)
        require(origin.userInfo == null && origin.path.isEmpty() && origin.query == null && origin.fragment == null)
    }

    private fun isOrdinaryFile(path: Path): Boolean = runCatching {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        attributes.isRegularFile && !attributes.isOther && !Files.isSymbolicLink(path)
    }.getOrDefault(false)

    private companion object {
        const val EXPLICIT_CONFIGURATION_ENV = "HUITAI_DESKTOP_CONFIG_FILE"
        const val WEBSOCKET_URL = "business.backend.websocket-url"
        const val LOCAL_ORIGIN = "business.backend.local-origin"
    }
}
