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
        assertTrue(script.contains("loginGateComposed"))
        assertTrue(script.contains("businessShellHiddenWhileSignedOut"))
        assertTrue(script.contains("brandLogoDecoded"))
        assertFalse(script.contains("shellComposed"))
        assertFalse(script.contains("mascotDecoded"))
        assertFalse(script.contains("sidebarNavigationComposed"))
        assertFalse(script.contains("assistantInitiallyCollapsed"))
        assertFalse(script.contains("HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY"))
        assertTrue(script.contains("\$child.WaitForExit(30000)"))
        assertFalse(script.contains("HuitaiBusinessDesktop.exe"))
        assertFalse(script.any { it.code > 0x7f }, "Windows PowerShell 5 smoke script must remain ASCII-decodable")
    }

    @Test
    fun `idea run configurations start backend and frontend independently without demo identity`() {
        val root = Path.of("..", "..")
        val backend = root.resolve(".run/Business Backend.run.xml").toFile().readText()
        val frontend = root.resolve(".run/Business Frontend.run.xml").toFile().readText()
        val stableBuildParameters = listOf(
            "--no-parallel",
            "--no-build-cache",
            "-Pkotlin.incremental=false",
            "-Pkotlin.compiler.execution.strategy=in-process",
        )

        assertTrue(backend.contains(":app:runBusinessBackendDevelopment"))
        assertFalse(backend.contains("runBusinessFrontendDevelopment"))
        assertTrue(frontend.contains(":app:runBusinessFrontendDevelopment"))
        assertFalse(frontend.contains("runBusinessBackendDevelopment"))
        assertTrue(frontend.contains("HUITAI_DESKTOP_EXTERNAL_BACKEND"))
        assertTrue(frontend.contains("HUITAI_DESKTOP_CONFIG_FILE"))
        assertTrue(
            frontend.contains(
                "\$PROJECT_DIR\$/business-desktop/config/business-desktop-development.properties",
            ),
        )
        assertFalse(backend.contains("HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY"))
        assertFalse(frontend.contains("HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY"))
        stableBuildParameters.forEach { parameter ->
            assertTrue(backend.contains(parameter), "backend run configuration must contain $parameter")
            assertTrue(frontend.contains(parameter), "frontend run configuration must contain $parameter")
        }
    }
}
