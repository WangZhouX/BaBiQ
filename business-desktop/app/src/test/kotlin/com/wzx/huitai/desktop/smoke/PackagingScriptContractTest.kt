package com.wzx.huitai.desktop.smoke

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackagingScriptContractTest {
    @Test
    fun `packaged smoke selects only the canonical compose MSI and EXE outputs`() {
        val script = Path.of("..", "scripts", "smoke-packaged-distribution.ps1").toFile().readText()

        assertTrue(script.contains("compose\\binaries\\main\\msi"))
        assertTrue(script.contains("compose\\binaries\\main\\exe"))
        assertTrue(script.contains("-Filter '*.msi'"))
        assertTrue(script.contains("-Filter '*.exe'"))
        assertFalse(script.contains("Get-ChildItem -LiteralPath \$appBuild -Recurse"))
    }

    @Test
    fun `packaged smoke launches only the Xiangniao branded executable`() {
        val script = Path.of("..", "scripts", "smoke-packaged-distribution.ps1").toFile().readText()

        assertTrue(script.contains("\$expectedProductName = -join ([char[]]@(0x7FD4, 0x9E1F, 0x5F8B, 0x667A, 0x684C, 0x9762, 0x7AEF))"))
        assertTrue(script.contains("\$desktopLauncherName = \"\$expectedProductName.exe\""))
        assertTrue(script.contains("-Filter \$desktopLauncherName"))
        assertTrue(script.contains("ProductName"))
        assertTrue(script.contains("[void]\$view.Execute()"))
        assertTrue(script.contains("\$expectedInstallerProductName = \"\$expectedProductName Installer\""))
        assertTrue(script.contains("\$expectedInstallerDescription = \"Installer of \$expectedProductName\""))
        assertTrue(script.contains("\$packageExeVersion.FileDescription -eq \$expectedInstallerDescription"))
        assertTrue(script.contains("windowComposed"))
        assertTrue(script.contains("brandLogoDecoded"))
        assertTrue(script.contains("mascotDecoded"))
        assertTrue(script.contains("topNavigationComposed"))
        assertTrue(script.contains("assistantInitiallyCollapsed"))
        assertTrue(script.contains("\$child.WaitForExit(30000)"))
        assertFalse(script.contains("HuitaiBusinessDesktop.exe"))
        assertFalse(script.any { it.code > 0x7f }, "Windows PowerShell 5 smoke script must remain ASCII-decodable")
    }
}
