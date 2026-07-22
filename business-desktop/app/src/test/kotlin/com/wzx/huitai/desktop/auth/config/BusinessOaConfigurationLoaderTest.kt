package com.wzx.huitai.desktop.auth.config

import com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessOaConfigurationLoaderTest {
    @Test
    fun `explicit file wins as a whole file and blank environment falls back to user file`() {
        val paths = paths()
        val explicit = Files.createTempFile("huitai-explicit", ".properties").toAbsolutePath()
        Files.writeString(explicit, validProperties(baseUrl = "https://explicit.example.test"))
        Files.createDirectories(paths.desktopConfiguration.parent)
        Files.writeString(paths.desktopConfiguration, validProperties(baseUrl = "https://user.example.test"))

        val explicitConfiguration = loader(mapOf(CONFIG_ENV to explicit.toString())).load(paths)
        val userConfiguration = loader(mapOf(CONFIG_ENV to "  ")).load(paths)

        assertEquals("https://explicit.example.test", explicitConfiguration.baseUrl)
        assertEquals("https://user.example.test", userConfiguration.baseUrl)
    }

    @Test
    fun `missing user file is atomically initialized from bundled default`() {
        val paths = paths()

        val configuration = loader().load(paths)

        assertEquals("https://bundled.example.test", configuration.baseUrl)
        assertTrue(paths.desktopConfiguration.exists())
        assertEquals(validProperties(), Files.readString(paths.desktopConfiguration))
        assertNoBootstrapTemporaryFiles(paths)
    }

    @Test
    fun `configuration created after final check wins atomic install race and is not overwritten`() {
        val paths = paths()
        val fileOperations = object : BusinessOaConfigurationBootstrapFileOperations by
            NioBusinessOaConfigurationBootstrapFileOperations {
            override fun createLink(target: Path, temporary: Path) {
                Files.writeString(target, validProperties(baseUrl = "https://user-won.example.test"))
                NioBusinessOaConfigurationBootstrapFileOperations.createLink(target, temporary)
            }
        }
        val loader = BusinessOaConfigurationLoader(
            environment = emptyMap(),
            bundledDefault = { ByteArrayInputStream(validProperties().toByteArray()) },
            bootstrapInstaller = AtomicBusinessOaConfigurationBootstrap(fileOperations),
        )

        assertEquals("https://user-won.example.test", loader.load(paths).baseUrl)
        assertEquals("https://user-won.example.test", loader().load(paths).baseUrl)
        assertNoBootstrapTemporaryFiles(paths)
    }

    @Test
    fun `write failure removes bootstrap temporary file`() {
        val paths = paths()
        val fileOperations = object : BusinessOaConfigurationBootstrapFileOperations by
            NioBusinessOaConfigurationBootstrapFileOperations {
            override fun writeAndForce(temporary: Path, source: java.io.InputStream) {
                Files.writeString(temporary, "partial")
                throw IOException("injected write failure")
            }
        }
        val loader = BusinessOaConfigurationLoader(
            environment = emptyMap(),
            bundledDefault = { ByteArrayInputStream(validProperties().toByteArray()) },
            bootstrapInstaller = AtomicBusinessOaConfigurationBootstrap(fileOperations),
        )

        assertCode(BusinessOaConfigurationErrorCode.CONFIG_UNAVAILABLE) { loader.load(paths) }
        assertFalse(paths.desktopConfiguration.exists())
        assertNoBootstrapTemporaryFiles(paths)
    }

    @Test
    fun `link install failure removes bootstrap temporary file`() {
        val paths = paths()
        val fileOperations = object : BusinessOaConfigurationBootstrapFileOperations by
            NioBusinessOaConfigurationBootstrapFileOperations {
            override fun createLink(target: Path, temporary: Path) {
                throw IOException("injected link failure")
            }
        }
        val loader = BusinessOaConfigurationLoader(
            environment = emptyMap(),
            bundledDefault = { ByteArrayInputStream(validProperties().toByteArray()) },
            bootstrapInstaller = AtomicBusinessOaConfigurationBootstrap(fileOperations),
        )

        assertCode(BusinessOaConfigurationErrorCode.CONFIG_UNAVAILABLE) { loader.load(paths) }
        assertFalse(paths.desktopConfiguration.exists())
        assertNoBootstrapTemporaryFiles(paths)
    }

    @Test
    fun `production bundled default targets cloud OA`() {
        val configuration = BusinessOaConfigurationLoader(environment = emptyMap()).load(paths())

        assertEquals("https://cloud.huitaikeji.cn", configuration.baseUrl)
        assertEquals("/law-api", configuration.apiPrefix)
        assertEquals(2, configuration.platformId)
        assertFalse(configuration.allowInsecureHttp)
    }

    @Test
    fun `selected file must contain every required property instead of merging defaults`() {
        val paths = paths()
        Files.createDirectories(paths.desktopConfiguration.parent)
        Files.writeString(paths.desktopConfiguration, "huitai.oa.base-url=https://user.example.test\n")

        assertCode(BusinessOaConfigurationErrorCode.CONFIG_INVALID) { loader().load(paths) }
    }

    @Test
    fun `explicit path must be absolute regular file and not a symbolic link`() {
        val paths = paths()
        assertCode(BusinessOaConfigurationErrorCode.CONFIG_INVALID) {
            loader(mapOf(CONFIG_ENV to "relative.properties")).load(paths)
        }

        val directory = Files.createTempDirectory("huitai-config-directory").toAbsolutePath()
        assertCode(BusinessOaConfigurationErrorCode.CONFIG_INVALID) {
            loader(mapOf(CONFIG_ENV to directory.toString())).load(paths)
        }

        val target = Files.createTempFile("huitai-config-target", ".properties")
        Files.writeString(target, validProperties())
        val link = target.resolveSibling("huitai-config-link-${System.nanoTime()}.properties")
        if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) {
            assertCode(BusinessOaConfigurationErrorCode.CONFIG_INVALID) {
                loader(mapOf(CONFIG_ENV to link.toAbsolutePath().toString())).load(paths)
            }
        }
    }

    @Test
    fun `unsafe user configuration remains invalid instead of unavailable`() {
        val directoryPaths = paths()
        Files.createDirectory(directoryPaths.desktopConfiguration)
        assertCode(BusinessOaConfigurationErrorCode.CONFIG_INVALID) { loader().load(directoryPaths) }

        val linkedPaths = paths()
        val target = Files.createTempFile("huitai-user-config-target", ".properties")
        Files.writeString(target, validProperties())
        val linkCreated = runCatching {
            Files.createSymbolicLink(linkedPaths.desktopConfiguration, target)
        }.isSuccess
        if (linkCreated) {
            assertCode(BusinessOaConfigurationErrorCode.CONFIG_INVALID) { loader().load(linkedPaths) }
        }
    }

    @Test
    fun `rejects malformed URLs prefix timeout and insecure public HTTP`() {
        listOf(
            validProperties(baseUrl = "https://user@example.test"),
            validProperties(baseUrl = "https://example.test?tenant=1"),
            validProperties(baseUrl = "https://example.test/#fragment"),
            validProperties(apiPrefix = "law-api"),
            validProperties(apiPrefix = "/law-api/../admin"),
            validProperties(timeout = "999"),
            validProperties(timeout = "120001"),
            validProperties(baseUrl = "http://8.8.8.8", allowInsecure = "true"),
            validProperties(baseUrl = "http://localhost", allowInsecure = "false"),
        ).forEach { contents ->
            assertInvalid(contents)
        }
    }

    @Test
    fun `allows explicitly enabled HTTP only for loopback and RFC1918 addresses`() {
        listOf("localhost", "127.0.0.1", "10.0.0.8", "172.16.4.2", "172.31.255.254", "192.168.3.4")
            .forEach { host ->
                val configuration = loadText(validProperties(baseUrl = "http://$host", allowInsecure = "true"))
                assertEquals("http://$host", configuration.baseUrl)
            }
    }

    @Test
    fun `rejects sensitive property keys regardless of case or namespace`() {
        listOf(
            "password",
            "auth.Token",
            "client.SECRET.value",
            "vendor.api-key",
            "vendor.api_key",
            "vendor.apiKey",
        )
            .forEach { key -> assertInvalid(validProperties() + "$key=must-not-be-read\n") }
    }

    @Test
    fun `read and bootstrap failures expose only stable error codes and no paths`() {
        val paths = paths()
        val missing = paths.root.resolve("private-company-config.properties").toAbsolutePath()
        val error = assertFailsWith<BusinessOaConfigurationException> {
            loader(mapOf(CONFIG_ENV to missing.toString())).load(paths)
        }

        assertEquals(BusinessOaConfigurationErrorCode.CONFIG_UNAVAILABLE, error.code)
        assertEquals("CONFIG_UNAVAILABLE", error.message)
        assertFalse(error.toString().contains(missing.toString()))
        assertFalse(loader().toString().contains(paths.root.toString()))
    }

    private fun assertInvalid(contents: String) {
        assertCode(BusinessOaConfigurationErrorCode.CONFIG_INVALID) { loadText(contents) }
    }

    private fun loadText(contents: String): BusinessOaConfiguration {
        val paths = paths()
        val file = Files.createTempFile("huitai-selected-config", ".properties").toAbsolutePath()
        Files.writeString(file, contents)
        return loader(mapOf(CONFIG_ENV to file.toString())).load(paths)
    }

    private fun assertCode(code: BusinessOaConfigurationErrorCode, block: () -> Unit) {
        val error = assertFailsWith<BusinessOaConfigurationException>(block = block)
        assertEquals(code, error.code)
        assertEquals(code.name, error.message)
    }

    private fun assertNoBootstrapTemporaryFiles(paths: BusinessDesktopRuntimePaths) {
        val temporaryFiles = Files.list(paths.desktopConfiguration.parent).use { entries ->
            entries
                .map { it.fileName.toString() }
                .filter { it.startsWith("business-desktop-") && it.endsWith(".tmp") }
                .toList()
        }
        assertTrue(temporaryFiles.isEmpty(), "bootstrap temporary files remain: $temporaryFiles")
    }

    private fun paths() = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-config-home"))

    private fun loader(environment: Map<String, String> = emptyMap()) = BusinessOaConfigurationLoader(
        environment = environment,
        bundledDefault = { ByteArrayInputStream(validProperties().toByteArray()) },
    )

    private fun validProperties(
        baseUrl: String = "https://bundled.example.test",
        apiPrefix: String = "/law-api",
        timeout: String = "30000",
        allowInsecure: String = "false",
    ) = """
        huitai.oa.base-url=$baseUrl
        huitai.oa.api-prefix=$apiPrefix
        huitai.oa.platform-id=2
        huitai.oa.request-timeout-ms=$timeout
        huitai.oa.service-agreement-url=https://example.test/agreement.html
        huitai.oa.privacy-policy-url=https://example.test/privacy.html
        huitai.oa.allow-insecure-http=$allowInsecure
    """.trimIndent() + "\n"

    private companion object {
        const val CONFIG_ENV = "HUITAI_DESKTOP_CONFIG_FILE"
    }
}
