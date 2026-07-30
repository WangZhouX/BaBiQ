package com.wzx.huitai.desktop.auth.config

import com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BusinessLegalLinksLoaderTest {
    @Test
    fun `loads only HTTPS legal links from bundled properties`() {
        val paths = paths()
        val loaded = loader().load(paths)

        assertEquals("https://example.test/agreement.html", loaded.serviceAgreementUrl)
        assertEquals("https://example.test/privacy.html", loaded.privacyPolicyUrl)
    }

    @Test
    fun `explicit file is selected without merging and legacy OA keys are rejected`() {
        val paths = paths()
        val file = Files.createTempFile("business-legal-links", ".properties")
        Files.writeString(
            file,
            "business.legal.service-agreement-url=https://example.test/agreement.html\n" +
                "business.legal.privacy-policy-url=https://example.test/privacy.html\n" +
                "huitai.oa.base-url=https://remote.example.test\n",
        )

        assertFailsWith<BusinessLegalLinksConfigurationException> {
            BusinessLegalLinksLoader(
                environment = mapOf("HUITAI_DESKTOP_CONFIG_FILE" to file.toAbsolutePath().toString()),
                bundledDefault = { ByteArrayInputStream(validProperties().toByteArray()) },
            ).load(paths)
        }
    }

    @Test
    fun `rejects every property outside the exact legal link allowlist`() {
        listOf(
            "spring.config.import=file:C:/outside/application.properties",
            "HUITAI_OA_BASE_URL=http://192.168.1.20:48080",
            "business.unexpected-property=true",
        ).forEach { forbiddenProperty ->
            val paths = paths()
            val file = Files.createTempFile("business-legal-links-unknown", ".properties")
            Files.writeString(file, validProperties() + "$forbiddenProperty\n")

            val failure = assertFailsWith<BusinessLegalLinksConfigurationException> {
                BusinessLegalLinksLoader(
                    environment = mapOf("HUITAI_DESKTOP_CONFIG_FILE" to file.toAbsolutePath().toString()),
                    bundledDefault = { ByteArrayInputStream(validProperties().toByteArray()) },
                ).load(paths)
            }

            assertEquals(BusinessLegalLinksConfigurationErrorCode.CONFIG_INVALID, failure.code)
        }
    }

    @Test
    fun `rejects non HTTPS legal links`() {
        val paths = paths()
        val file = Files.createTempFile("business-legal-links", ".properties")
        Files.writeString(
            file,
            "business.legal.service-agreement-url=http://example.test/agreement.html\n" +
                "business.legal.privacy-policy-url=https://example.test/privacy.html\n",
        )

        assertFailsWith<BusinessLegalLinksConfigurationException> {
            BusinessLegalLinksLoader(
                environment = mapOf("HUITAI_DESKTOP_CONFIG_FILE" to file.toAbsolutePath().toString()),
                bundledDefault = { ByteArrayInputStream(validProperties().toByteArray()) },
            ).load(paths)
        }
    }

    private fun loader() = BusinessLegalLinksLoader(
        environment = emptyMap(),
        bundledDefault = { ByteArrayInputStream(validProperties().toByteArray()) },
    )

    private fun validProperties() =
        "business.legal.service-agreement-url=https://example.test/agreement.html\n" +
            "business.legal.privacy-policy-url=https://example.test/privacy.html\n"

    private fun paths() =
        BusinessDesktopRuntimePaths.create(Files.createTempDirectory("business-legal-links-home"))
}
