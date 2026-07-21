package com.wzx.huitai.desktop.smoke

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackagedSmokeWindowCompositionTest {
    @Test
    fun `readiness reflects decoded brand resources and the collapsed initial assistant`() {
        val ready = buildPackagedSmokeUiReadiness(
            assistantInitiallyCollapsed = true,
            productName = "翔鸟律智桌面端",
            decodeLogo = {},
            decodeMascot = {},
        )
        val missingMascot = buildPackagedSmokeUiReadiness(
            assistantInitiallyCollapsed = true,
            productName = "翔鸟律智桌面端",
            decodeLogo = {},
            decodeMascot = { error("missing mascot") },
        )

        assertTrue(ready.windowComposed)
        assertTrue(ready.brandLogoDecoded)
        assertTrue(ready.mascotDecoded)
        assertTrue(ready.topNavigationComposed)
        assertTrue(ready.assistantInitiallyCollapsed)
        assertFalse(missingMascot.mascotDecoded)
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
        val effectBinding = source.substring(effectIndex, source.indexOf("when (val dialog", effectIndex))
        assertTrue(effectBinding.contains("closeBusinessDesktop("))
        assertTrue(effectBinding.indexOf("shutdown = { root.shutdown() }") < effectBinding.indexOf("exitApplication = ::exitApplication"))
    }
}
