package com.wzx.huitai.desktop.smoke

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PackagedSmokeCompositionCoordinatorTest {
    @Test
    fun `publishes only after committed window composition and only once`() = runTest {
        val home = Files.createTempDirectory("packaged-smoke-composition")
        val reportPath = home.resolve("report.json")
        var evidenceReads = 0
        val coordinator = PackagedSmokeCompositionCoordinator(
            probe = PackagedSmokeProbe(reportPath),
            evidenceProvider = {
                evidenceReads += 1
                validEvidence(home)
            },
        )

        assertFalse(Files.exists(reportPath))
        assertTrue(
            coordinator.onWindowCompositionCommitted(PackagedSmokeUiReadiness.ready()),
        )
        assertTrue(Files.exists(reportPath))
        assertFalse(
            coordinator.onWindowCompositionCommitted(PackagedSmokeUiReadiness.ready()),
        )
        assertEquals(1, evidenceReads)
    }

    @Test
    fun `failed composition publication can be corrected without duplicate reports`() = runTest {
        val home = Files.createTempDirectory("packaged-smoke-composition-retry")
        val reportPath = home.resolve("report.json")
        var evidenceReads = 0
        val coordinator = PackagedSmokeCompositionCoordinator(
            probe = PackagedSmokeProbe(reportPath),
            evidenceProvider = {
                evidenceReads += 1
                validEvidence(home)
            },
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.onWindowCompositionCommitted(PackagedSmokeUiReadiness.notReady())
        }
        assertFalse(Files.exists(reportPath))
        assertTrue(
            coordinator.onWindowCompositionCommitted(PackagedSmokeUiReadiness.ready()),
        )
        assertFalse(
            coordinator.onWindowCompositionCommitted(PackagedSmokeUiReadiness.ready()),
        )
        assertEquals(2, evidenceReads)
    }

    private fun validEvidence(home: java.nio.file.Path): PackagedSmokeEvidence {
        val runtimeRoot = home.resolve(".huitai-agent-desktop")
        val desktopRoot = runtimeRoot.resolve("desktop")
        val agentRoot = runtimeRoot.resolve("agent")
        return PackagedSmokeEvidence(
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
    }
}
