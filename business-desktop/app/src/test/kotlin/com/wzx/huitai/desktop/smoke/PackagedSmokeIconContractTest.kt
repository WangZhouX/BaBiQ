package com.wzx.huitai.desktop.smoke

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class PackagedSmokeIconContractTest {
    @Test
    fun `packaged smoke compares launcher pixels against repository brand icon`() {
        val script = Path.of("..", "scripts", "smoke-packaged-distribution.ps1").toFile().readText()
        val helper = Path.of("..", "scripts", "packaged-smoke-icon.ps1")

        assertTrue(helper.toFile().isFile)
        assertTrue(script.contains("packaged-smoke-icon.ps1"))
        assertTrue(script.contains("Test-LauncherBrandIcon"))
        assertTrue(script.contains("brand\\xiangniao.ico"))
    }

    @Test
    fun `Windows default executable icon is rejected as a nonmatching brand icon`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val helper = Path.of("..", "scripts", "packaged-smoke-icon.ps1").toAbsolutePath().normalize()
        val brand = Path.of("src", "main", "resources", "brand", "xiangniao.ico").toAbsolutePath().normalize()
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val nonBrandExecutable = Path.of(systemRoot, "System32", "notepad.exe")
        val command = buildString {
            append(". '").append(helper.psQuoted()).append("'; ")
            append("if (Test-LauncherBrandIcon -LauncherPath '")
            append(nonBrandExecutable.psQuoted())
            append("' -BrandIconPath '")
            append(brand.psQuoted())
            append("') { exit 9 } else { exit 0 }")
        }
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            command,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        assertEquals(0, exitCode, output)
    }

    private fun Path.psQuoted(): String = toString().replace("'", "''")
}
