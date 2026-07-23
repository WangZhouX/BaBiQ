package com.wzx.huitai.desktop.smoke

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PackagedSmokeWindowCompositionTest {
    @Test
    fun `readiness reflects only the signed out login gate composition signals`() {
        val signals = PackagedSmokeUiCompositionSignals()
        assertFalse(signals.snapshot().windowComposed)
        assertFalse(signals.snapshot().loginGateComposed)
        assertFalse(signals.snapshot().shellComposed)

        signals.markWindowComposed()
        signals.markLoginGateComposed()
        val ready = buildPackagedSmokeUiReadiness(
            composition = signals.snapshot(),
            productName = PackagedSmokeUiReadiness.PRODUCT_NAME,
            decodeLogo = {},
        )
        signals.markShellComposed()
        val leakedShell = buildPackagedSmokeUiReadiness(
            composition = signals.snapshot(),
            productName = PackagedSmokeUiReadiness.PRODUCT_NAME,
            decodeLogo = {},
        )
        val logoFailure = IllegalStateException("missing logo")
        val reportedFailure = assertFailsWith<IllegalStateException> {
            buildPackagedSmokeUiReadiness(
                composition = signals.snapshot(),
                productName = PackagedSmokeUiReadiness.PRODUCT_NAME,
                decodeLogo = { throw logoFailure },
            )
        }

        assertTrue(ready.windowComposed)
        assertTrue(ready.loginGateComposed)
        assertTrue(ready.businessShellHiddenWhileSignedOut)
        assertFalse(leakedShell.businessShellHiddenWhileSignedOut)
        assertTrue(ready.brandLogoDecoded)
        assertSame(logoFailure, reportedFailure)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `publication waits for a frame and all real composition paths`() = runTest {
        val home = Files.createTempDirectory("packaged-smoke-frame")
        val report = home.resolve("report.json")
        val signals = PackagedSmokeUiCompositionSignals()
        val frame = CompletableDeferred<Unit>()
        val coordinator = PackagedSmokeCompositionCoordinator(
            probe = PackagedSmokeProbe(report),
            evidenceProvider = { validEvidence(home) },
        )

        val publication = launch {
            publishPackagedSmokeAfterCommittedFrame(
                coordinator = coordinator,
                compositionSignals = signals,
                productName = PackagedSmokeUiReadiness.PRODUCT_NAME,
                awaitFrame = { frame.await() },
                decodeLogo = {},
            )
        }
        runCurrent()
        assertFalse(Files.exists(report), "the report must not publish before a rendered frame")

        signals.markWindowComposed()
        signals.markLoginGateComposed()
        frame.complete(Unit)
        publication.join()

        assertTrue(Files.exists(report))
    }

    @Test
    fun `main publishes smoke only through the committed Window composition effect`() {
        val source = Path.of("src", "main", "kotlin", "com", "wzx", "huitai", "desktop", "Main.kt")
            .toFile()
            .readText()
        val applicationIndex = source.indexOf("application {")
        val windowIndex = source.indexOf("Window(", applicationIndex)
        val gateIndex = source.indexOf("BusinessLoginGate(", windowIndex)
        val loginIndex = source.indexOf("login = {", gateIndex)
        val readyIndex = source.indexOf("ready = {", loginIndex)
        val shellIndex = source.indexOf("BusinessDesktopShell(", readyIndex)
        val decisionIndex = source.indexOf("when (val dialog = decisionState.activeDialog)", shellIndex)
        val effectIndex = source.indexOf("PackagedSmokeWindowCompositionEffect(", gateIndex)

        assertTrue(applicationIndex >= 0)
        assertTrue(windowIndex > applicationIndex)
        assertTrue(gateIndex > windowIndex)
        assertTrue(loginIndex > gateIndex)
        assertTrue(readyIndex > loginIndex)
        assertTrue(shellIndex > readyIndex)
        assertTrue(decisionIndex > shellIndex)
        assertTrue(effectIndex > decisionIndex)
        assertFalse(source.substring(0, applicationIndex).contains("smokeProbe.write"))
        assertFalse(source.substring(0, applicationIndex).contains("packagedSmokeEvidence()"))
        assertTrue(source.contains("smokeUiCompositionSignals.markWindowComposed()"))
        assertTrue(source.contains("smokeUiCompositionSignals.markLoginGateComposed()"))
        assertTrue(source.contains("onShellComposed = smokeUiCompositionSignals::markShellComposed"))
        assertFalse(source.contains("markBusinessShellHiddenWhileSignedOut"))
        assertTrue(Regex("""BusinessDesktopShell\(""").findAll(source).count() == 1)
        assertTrue(source.contains("enabled = gate == BusinessAccessGateState.SIGNED_OUT"))
        assertFalse(source.contains("HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY"))
        assertTrue(source.contains("view.production.logoutController.logout()"))
        assertFalse(source.contains("authenticationOrchestrator.logout()"))
        assertTrue(source.contains("compositionSignals = smokeUiCompositionSignals"))
        val effectBinding = source.substring(effectIndex, source.indexOf("    } finally", effectIndex))
        assertTrue(effectBinding.contains("onFailure = { failure ->"))
        assertTrue(effectBinding.contains(".error(") && effectBinding.contains("failure"))
        assertTrue(effectBinding.contains("closeBusinessDesktop("))
        assertTrue(effectBinding.indexOf("shutdown = { root.shutdown() }") < effectBinding.indexOf("exitApplication = ::exitApplication"))
    }

    private fun validEvidence(home: Path): PackagedSmokeEvidence {
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
