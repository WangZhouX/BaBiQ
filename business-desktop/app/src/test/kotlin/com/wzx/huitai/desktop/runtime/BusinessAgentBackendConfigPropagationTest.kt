package com.wzx.huitai.desktop.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessAgentBackendConfigPropagationTest {
    @Test
    fun `development configuration remains legal only while OA address uses child environment`() {
        val configuration = Path.of("..", "config", "business-desktop-development.properties")
            .toAbsolutePath()
            .normalize()
        val properties = Properties().also { loaded ->
            Files.newInputStream(configuration).use(loaded::load)
        }

        assertEquals(
            setOf(
                "business.legal.service-agreement-url",
                "business.legal.privacy-policy-url",
            ),
            properties.stringPropertyNames(),
        )
        assertFalse(Files.readString(configuration).contains("huitai.oa", ignoreCase = true))
    }

    @Test
    fun `child command receives one normalized controlled configuration location`() {
        val paths = pathsWithConfiguration("huitai-backend-config-command")
        val request = request(paths)

        try {
            val expected = paths.desktopConfiguration.toAbsolutePath().normalize()
            val locations = request.command().filter {
                it.startsWith("--spring.config.additional-location=")
            }

            assertEquals(
                listOf("--spring.config.additional-location=file:$expected"),
                locations,
            )
            assertTrue(expected.startsWith(paths.root.toAbsolutePath().normalize()))
        } finally {
            request.close()
        }
    }

    @Test
    fun `selected loopback port is bound into the exact websocket and upload origin`() {
        val paths = pathsWithConfiguration("huitai-backend-origin-command")
        val request = request(paths)

        try {
            assertEquals("http://127.0.0.1:43117", request.identity.localOrigin)
            assertEquals(
                listOf("--babiq.ws.allowed-origins=http://127.0.0.1:43117"),
                request.command().filter { it.startsWith("--babiq.ws.allowed-origins=") },
            )
        } finally {
            request.close()
        }
    }

    @Test
    fun `child command fails closed when controlled configuration is missing`() {
        val paths = BusinessDesktopRuntimePaths.create(
            Files.createTempDirectory("huitai-backend-config-missing"),
        )
        val request = request(paths)

        try {
            val failure = assertFailsWith<IllegalStateException> {
                request.command()
            }

            assertEquals(CONFIGURATION_UNAVAILABLE, failure.message)
        } finally {
            request.close()
        }
        assertFalse(Files.exists(paths.agentSessionToken))
    }

    @Test
    fun `child command fails closed when controlled configuration is not a regular file`() {
        val paths = BusinessDesktopRuntimePaths.create(
            Files.createTempDirectory("huitai-backend-config-directory"),
        )
        Files.createDirectory(paths.desktopConfiguration)
        val request = request(paths)

        try {
            val failure = assertFailsWith<IllegalStateException> {
                request.command()
            }

            assertEquals(CONFIGURATION_UNAVAILABLE, failure.message)
        } finally {
            request.close()
        }
    }

    @Test
    fun `child command rejects a configuration link escaping the controlled runtime`() {
        val paths = BusinessDesktopRuntimePaths.create(
            Files.createTempDirectory("huitai-backend-config-link"),
        )
        val outside = Files.createTempFile("huitai-backend-config-outside", ".properties")
        Files.writeString(outside, LEGAL_CONFIGURATION)
        val linkCreated = runCatching {
            Files.createSymbolicLink(paths.desktopConfiguration, outside)
        }.isSuccess
        if (!linkCreated) return
        val request = request(paths)

        try {
            val failure = assertFailsWith<IllegalStateException> {
                request.command()
            }

            assertEquals(CONFIGURATION_UNAVAILABLE, failure.message)
            assertEquals(LEGAL_CONFIGURATION, Files.readString(outside))
        } finally {
            request.close()
        }
    }

    private fun pathsWithConfiguration(prefix: String): BusinessDesktopRuntimePaths =
        BusinessDesktopRuntimePaths.create(Files.createTempDirectory(prefix)).also { paths ->
            Files.writeString(paths.desktopConfiguration, LEGAL_CONFIGURATION)
        }

    private fun request(paths: BusinessDesktopRuntimePaths): BusinessAgentLaunchRequest =
        BusinessAgentLaunchRequest.create(
            paths = paths,
            desktopInstanceId = "installation-id",
            backendJar = paths.root.resolve("backend/babiq-server.jar"),
            javaExecutable = Path.of("java"),
            backendKeyStorePassword = "backend-test-password".toCharArray(),
            port = 43_117,
        )

    private companion object {
        const val CONFIGURATION_UNAVAILABLE = "business backend configuration is unavailable"
        const val LEGAL_CONFIGURATION =
            "business.legal.service-agreement-url=https://example.test/agreement.html\n" +
                "business.legal.privacy-policy-url=https://example.test/privacy.html\n"
    }
}
