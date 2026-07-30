package com.wzx.huitai.desktop.auth.config

import com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BusinessBackendConnectionConfigurationLoaderTest {
    @Test
    fun `loads backend websocket url and origin from explicit desktop properties`() {
        val home = Files.createTempDirectory("business-backend-config")
        val paths = BusinessDesktopRuntimePaths.create(home)
        val file = Files.createTempFile("business-backend-config", ".properties")
        Files.writeString(
            file,
            validProperties("49391"),
        )

        val loaded = BusinessBackendConnectionConfigurationLoader(
            environment = mapOf("HUITAI_DESKTOP_CONFIG_FILE" to file.toAbsolutePath().toString()),
        ).load(paths)

        assertEquals("ws://127.0.0.1:49391/ws/agent", loaded.websocketUrl)
        assertEquals("http://127.0.0.1:49391", loaded.localOrigin)
    }

    @Test
    fun `rejects non loopback endpoint or mismatched origin`() {
        val home = Files.createTempDirectory("business-backend-config-invalid")
        val paths = BusinessDesktopRuntimePaths.create(home)
        listOf(
            "business.backend.websocket-url=ws://192.168.1.20:48080/ws/agent\n" +
                "business.backend.local-origin=http://192.168.1.20:48080\n",
            "business.backend.websocket-url=ws://127.0.0.1:49391/ws/agent\n" +
                "business.backend.local-origin=http://127.0.0.1:48080\n",
        ).forEach { content ->
            val file = Files.createTempFile("business-backend-config-invalid", ".properties")
            Files.writeString(file, content)
            assertFailsWith<IllegalArgumentException> {
                BusinessBackendConnectionConfigurationLoader(
                    environment = mapOf("HUITAI_DESKTOP_CONFIG_FILE" to file.toAbsolutePath().toString()),
                ).load(paths)
            }
        }
    }

    private fun validProperties(port: String) =
        "business.legal.service-agreement-url=https://example.test/agreement.html\n" +
            "business.legal.privacy-policy-url=https://example.test/privacy.html\n" +
            "business.backend.websocket-url=ws://127.0.0.1:$port/ws/agent\n" +
            "business.backend.local-origin=http://127.0.0.1:$port\n"
}
