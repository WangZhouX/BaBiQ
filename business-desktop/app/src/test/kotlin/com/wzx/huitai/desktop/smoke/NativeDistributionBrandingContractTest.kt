package com.wzx.huitai.desktop.smoke

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeDistributionBrandingContractTest {
    @Test
    fun `native distributions expose Xiangniao branding for MSI and EXE`() {
        val buildScript = Path.of("build.gradle.kts").toFile().readText()

        assertTrue(buildScript.contains("targetFormats(TargetFormat.Msi, TargetFormat.Exe)"))
        assertTrue(buildScript.contains("packageName = \"翔鸟律智桌面端\""))
        assertTrue(buildScript.contains("description = \"翔鸟律智桌面端\""))
        assertTrue(buildScript.contains("vendor = \"翔鸟律智\""))
        assertFalse(buildScript.contains("HuitaiBusinessDesktop"))
    }

    @Test
    fun `Windows package installs the formal icon shortcut and start menu entry`() {
        val buildScript = Path.of("build.gradle.kts").toFile().readText()

        assertTrue(buildScript.contains("windows {"))
        assertTrue(
            buildScript.contains(
                "iconFile.set(project.file(\"src/main/resources/brand/xiangniao.ico\"))",
            ),
        )
        assertTrue(buildScript.contains("shortcut = true"))
        assertTrue(buildScript.contains("menu = true"))
        assertTrue(buildScript.contains("menuGroup = \"翔鸟律智\""))
    }
}
