package com.wzx.huitai.desktop.smoke

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PackagedSmokeProbeTest {
    @Test
    fun `writes one redacted validated report for the real packaged runtime`() {
        val home = Files.createTempDirectory("packaged-smoke-home")
        val runtimeRoot = home.resolve(".huitai-agent-desktop")
        val desktopRoot = runtimeRoot.resolve("desktop")
        val agentRoot = runtimeRoot.resolve("agent")
        val reportPath = home.resolve("smoke-report.json")

        PackagedSmokeProbe(reportPath).write(
            PackagedSmokeEvidence(
                profile = "business-desktop",
                address = "127.0.0.1",
                port = 49_151,
                runtimeRoot = runtimeRoot,
                desktopRoot = desktopRoot,
                agentRoot = agentRoot,
                desktopDatabase = desktopRoot.resolve("data/business-desktop.db"),
                agentDatabase = agentRoot.resolve("data/babiq-business.db"),
                desktopKeyStore = desktopRoot.resolve("secrets/business-desktop.jceks"),
                agentKeyStore = agentRoot.resolve("secrets/business-agent.jceks"),
                tokenFile = agentRoot.resolve("session-token"),
                tokenFileDeleted = true,
                unauthorizedHandshakeRejected = true,
                authenticatedConnection = true,
                signedOutIdentityBound = true,
                childPid = 42_424,
                uiReadiness = PackagedSmokeUiReadiness.ready(),
            ),
        )

        val text = reportPath.readText()
        val report = Json.parseToJsonElement(text).jsonObject
        assertEquals("business-desktop", report.getValue("profile").jsonPrimitive.content)
        assertEquals("127.0.0.1", report.getValue("address").jsonPrimitive.content)
        assertEquals(49_151, report.getValue("port").jsonPrimitive.content.toInt())
        assertTrue(report.getValue("dynamicPort").jsonPrimitive.boolean)
        assertTrue(report.getValue("loopbackAddress").jsonPrimitive.boolean)
        assertTrue(report.getValue("tokenFileDeleted").jsonPrimitive.boolean)
        assertTrue(report.getValue("unauthorizedHandshakeRejected").jsonPrimitive.boolean)
        assertTrue(report.getValue("authenticatedConnection").jsonPrimitive.boolean)
        assertTrue(report.getValue("signedOutIdentityBound").jsonPrimitive.boolean)
        assertTrue(report.getValue("windowComposed").jsonPrimitive.boolean)
        assertTrue(report.getValue("loginGateComposed").jsonPrimitive.boolean)
        assertTrue(report.getValue("businessShellHiddenWhileSignedOut").jsonPrimitive.boolean)
        assertTrue(report.getValue("brandLogoDecoded").jsonPrimitive.boolean)
        assertFalse(report.containsKey("shellComposed"))
        assertFalse(report.containsKey("mascotDecoded"))
        assertFalse(report.containsKey("sidebarNavigationComposed"))
        assertFalse(report.containsKey("assistantInitiallyCollapsed"))
        assertEquals("翔鸟律智桌面端", report.getValue("productName").jsonPrimitive.content)
        assertEquals(42_424, report.getValue("childPid").jsonPrimitive.content.toLong())
        assertEquals(runtimeRoot.toAbsolutePath().normalize().toString(), report.getValue("runtimeRoot").jsonPrimitive.content)
        assertFalse(text.contains("token", ignoreCase = true) && text.contains("secret-value"))
        assertFalse(text.contains("password", ignoreCase = true))
        assertFalse(text.contains("desktopSessionId"))
        assertFalse(text.contains("authSessionId"))
        assertFalse(text.contains("secretRef"))
        assertTrue(
            Files.list(home).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } },
            "atomic report publication must not leave temporary files",
        )
    }

    @Test
    fun `rejects a report without successful window composition readiness`() {
        val home = Files.createTempDirectory("invalid-ui-packaged-smoke")
        val runtimeRoot = home.resolve(".huitai-agent-desktop")
        val desktopRoot = runtimeRoot.resolve("desktop")
        val agentRoot = runtimeRoot.resolve("agent")
        val evidence = PackagedSmokeEvidence(
            profile = "business-desktop",
            address = "127.0.0.1",
            port = 49_151,
            runtimeRoot = runtimeRoot,
            desktopRoot = desktopRoot,
            agentRoot = agentRoot,
            desktopDatabase = desktopRoot.resolve("data/business-desktop.db"),
            agentDatabase = agentRoot.resolve("data/babiq-business.db"),
            desktopKeyStore = desktopRoot.resolve("secrets/business-desktop.jceks"),
            agentKeyStore = agentRoot.resolve("secrets/business-agent.jceks"),
            tokenFile = agentRoot.resolve("session-token"),
            tokenFileDeleted = true,
            unauthorizedHandshakeRejected = true,
            authenticatedConnection = true,
            signedOutIdentityBound = true,
            childPid = 42_424,
        )

        val ready = PackagedSmokeUiReadiness.ready()
        val invalidReadiness = listOf(
            ready.copy(windowComposed = false),
            ready.copy(loginGateComposed = false),
            ready.copy(businessShellHiddenWhileSignedOut = false),
            ready.copy(brandLogoDecoded = false),
            ready.copy(productName = "wrong-product"),
        )
        invalidReadiness.forEachIndexed { index, readiness ->
            val report = home.resolve("report-$index.json")
            assertFailsWith<IllegalArgumentException> {
                PackagedSmokeProbe(report).write(evidence.copy(uiReadiness = readiness))
            }
            assertFalse(Files.exists(report))
        }

    }

    @Test
    fun `rejects reports that are not isolated authenticated signed out loopback runtimes`() {
        val home = Files.createTempDirectory("invalid-packaged-smoke")
        val runtimeRoot = home.resolve(".huitai-agent-desktop")
        val shared = runtimeRoot.resolve("shared")
        val evidence = PackagedSmokeEvidence(
            profile = "wrong-profile",
            address = "0.0.0.0",
            port = 0,
            runtimeRoot = runtimeRoot,
            desktopRoot = shared,
            agentRoot = shared,
            desktopDatabase = shared.resolve("same.db"),
            agentDatabase = shared.resolve("same.db"),
            desktopKeyStore = shared.resolve("same.jceks"),
            agentKeyStore = shared.resolve("same.jceks"),
            tokenFile = shared.resolve("session-token"),
            tokenFileDeleted = false,
            unauthorizedHandshakeRejected = false,
            authenticatedConnection = false,
            signedOutIdentityBound = false,
            childPid = 0,
        )

        assertFailsWith<IllegalArgumentException> {
            PackagedSmokeProbe(home.resolve("report.json")).write(evidence)
        }
        assertFalse(Files.exists(home.resolve("report.json")))
    }

    @Test
    fun `environment activation is explicit and does not expose report path in object text`() {
        assertNull(PackagedSmokeProbe.fromEnvironment(emptyMap()))
        val report = Files.createTempDirectory("smoke-env").resolve("report.json")
        val probe = PackagedSmokeProbe.fromEnvironment(
            mapOf(PackagedSmokeProbe.REPORT_ENV to report.toString()),
        )

        assertTrue(probe != null)
        assertFalse(probe.toString().contains(report.toString()))
    }
}
