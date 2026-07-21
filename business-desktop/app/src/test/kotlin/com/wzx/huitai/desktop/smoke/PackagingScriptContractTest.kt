package com.wzx.huitai.desktop.smoke

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackagingScriptContractTest {
    @Test
    fun `packaged smoke selects only the canonical compose MSI output`() {
        val script = Path.of("..", "scripts", "smoke-packaged-distribution.ps1").toFile().readText()

        assertTrue(script.contains("compose\\binaries\\main\\msi"))
        assertFalse(script.contains("Get-ChildItem -LiteralPath \$appBuild -Recurse"))
    }

    @Test
    fun `packaged smoke launches only the Xiangniao branded executable`() {
        val script = Path.of("..", "scripts", "smoke-packaged-distribution.ps1").toFile().readText()

        assertTrue(script.contains("\$desktopLauncherName = '翔鸟律智桌面端.exe'"))
        assertTrue(script.contains("-Filter \$desktopLauncherName"))
        assertFalse(script.contains("HuitaiBusinessDesktop.exe"))
    }
}
