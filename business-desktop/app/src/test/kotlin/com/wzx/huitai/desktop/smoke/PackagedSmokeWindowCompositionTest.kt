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
    fun `readiness reflects only real window shell and sidebar navigation composition signals`() {
        val signals = PackagedSmokeUiCompositionSignals()
        assertFalse(signals.snapshot().windowComposed)
        assertFalse(signals.snapshot().shellComposed)
        assertFalse(signals.snapshot().sidebarNavigationComposed)

        signals.markWindowComposed()
        signals.markShellComposed()
        val ready = buildPackagedSmokeUiReadiness(
            composition = signals.snapshot(),
            assistantInitiallyCollapsed = true,
            productName = PackagedSmokeUiReadiness.PRODUCT_NAME,
            decodeLogo = {},
            decodeMascot = {},
        )
        signals.markSidebarNavigationComposed()
        val mascotFailure = IllegalStateException("missing mascot")
        val reportedFailure = assertFailsWith<IllegalStateException> {
            buildPackagedSmokeUiReadiness(
                composition = signals.snapshot(),
                assistantInitiallyCollapsed = true,
                productName = PackagedSmokeUiReadiness.PRODUCT_NAME,
                decodeLogo = {},
                decodeMascot = { throw mascotFailure },
            )
        }

        assertTrue(ready.windowComposed)
        assertTrue(ready.shellComposed)
        assertTrue(ready.brandLogoDecoded)
        assertTrue(ready.mascotDecoded)
        assertFalse(ready.sidebarNavigationComposed)
        assertTrue(ready.assistantInitiallyCollapsed)
        assertSame(mascotFailure, reportedFailure)
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
                assistantInitiallyCollapsed = true,
                productName = PackagedSmokeUiReadiness.PRODUCT_NAME,
                awaitFrame = { frame.await() },
                decodeLogo = {},
                decodeMascot = {},
            )
        }
        runCurrent()
        assertFalse(Files.exists(report), "the report must not publish before a rendered frame")

        signals.markWindowComposed()
        signals.markShellComposed()
        signals.markSidebarNavigationComposed()
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
        val shellIndex = source.indexOf("BusinessDesktopShell(", windowIndex)
        val effectIndex = source.indexOf("PackagedSmokeWindowCompositionEffect(", shellIndex)

        assertTrue(applicationIndex >= 0)
        assertTrue(windowIndex > applicationIndex)
        assertTrue(shellIndex > windowIndex)
        assertTrue(effectIndex > shellIndex)
        assertFalse(source.substring(0, applicationIndex).contains("smokeProbe.write"))
        assertFalse(source.substring(0, applicationIndex).contains("packagedSmokeEvidence()"))
        assertTrue(source.contains("assistantInitiallyCollapsed = !assistantExpanded"))
        assertTrue(source.contains("smokeUiCompositionSignals.markWindowComposed()"))
        assertTrue(source.contains("onShellComposed = smokeUiCompositionSignals::markShellComposed"))
        assertTrue(source.contains("onSidebarNavigationComposed = smokeUiCompositionSignals::markSidebarNavigationComposed"))
        val legacyConnection = "onTop" + "NavigationComposed = smokeUiCompositionSignals::markTop" + "NavigationComposed"
        assertFalse(source.contains(legacyConnection))
        assertTrue(source.contains("compositionSignals = smokeUiCompositionSignals"))
        val effectBinding = source.substring(effectIndex, source.indexOf("when (val dialog", effectIndex))
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
