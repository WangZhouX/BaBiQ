package com.wzx.huitai.desktop.build

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistributionBuildLogicTest {
    @Test
    fun `distribution builds and bundles the business backend before every native package`() {
        val buildScript = Path.of("build.gradle.kts").readText()

        assertContains(buildScript, "packageBusinessBackendJar")
        assertContains(buildScript, "prepareBundledBusinessBackend")
        assertContains(buildScript, "retainRuntimeJavaExecutable")
        assertContains(buildScript, "smokePackagedDistribution")
        assertContains(buildScript, "mvnw.cmd")
        assertContains(buildScript, "-DskipTests")
        assertContains(buildScript, "babiq-server-0.0.1-SNAPSHOT.jar")
        assertContains(buildScript, "common/backend/babiq-server.jar")
        assertContains(buildScript, "appResourcesRootDir")
        assertContains(buildScript, "prepareAppResources")
        assertContains(buildScript, "packageDistributionForCurrentOS")
        assertContains(buildScript, "createReleaseDistributable")
        assertContains(buildScript, "packageMsi")
        assertContains(buildScript, "packageExe")
        assertContains(buildScript, "includeAllModules = true")
        assertContains(buildScript, "java.exe")
        assertContains(buildScript, "smoke-packaged-distribution.ps1")
    }

    @Test
    fun `packaged smoke harness extracts the msi and validates the child lifecycle`() {
        val script = Path.of("..", "scripts", "smoke-packaged-distribution.ps1")
        assertTrue(script.toFile().isFile, "packaged smoke PowerShell harness must exist")
        val source = script.readText()

        assertContains(source, "msiexec.exe")
        assertContains(source, "/a")
        assertContains(source, "HUITAI_DESKTOP_SMOKE_REPORT")
        assertContains(source, "compose\\binaries\\main\\msi")
        assertContains(source, "compose\\binaries\\main\\exe")
        assertContains(source, "\$desktopLauncherName = \"\$expectedProductName.exe\"")
        assertContains(source, "120")
        assertContains(source, "babiq-server.jar")
        assertContains(source, ".huitai-agent-desktop")
        assertContains(source, "tokenFileDeleted")
        assertContains(source, "unauthorizedHandshakeRejected")
        assertContains(source, "authenticatedConnection")
        assertContains(source, "signedOutIdentityBound")
        assertContains(source, "windowComposed")
        assertContains(source, "brandLogoDecoded")
        assertContains(source, "mascotDecoded")
        assertContains(source, "sidebarNavigationComposed")
        assertFalse(source.contains("top" + "NavigationComposed"))
        assertContains(source, "assistantInitiallyCollapsed")
        assertContains(source, "childPid")
    }
}
