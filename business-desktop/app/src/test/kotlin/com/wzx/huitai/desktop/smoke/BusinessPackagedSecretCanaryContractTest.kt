package com.wzx.huitai.desktop.smoke

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BusinessPackagedSecretCanaryContractTest {
    @Test
    fun `packaged smoke audits complete temporary tree after shutdown and before cleanup`() {
        val script = Path.of("..", "scripts", "smoke-packaged-distribution.ps1").toFile().readText()

        val shutdownBarrier = script.indexOf("Owned smoke processes remain alive")
        val audit = script.indexOf(
            "Assert-NoSecretMarkerInTree -Root \$temporaryRoot -Marker \$secretMarker",
        )
        val cleanup = script.indexOf(
            "Remove-SmokeTemporaryRoot -Root \$temporaryRoot -SystemTemp \$systemTemp",
        )

        assertTrue(shutdownBarrier >= 0)
        assertTrue(audit > shutdownBarrier, "secret audit must run after every owned process has stopped")
        assertTrue(cleanup > audit, "temporary artifacts must not be deleted before the secret audit")
        assertTrue(script.contains("Get-ChildItem -LiteralPath \$Root -Recurse -Force -ErrorAction Stop"))
        assertFalse(script.contains("Get-ChildItem -LiteralPath \$Root -Recurse -File"))
        assertTrue(script.contains("\$processTemp = Join-Path \$temporaryRoot 'process-temp'"))
        assertTrue(script.contains("TEMP = \$processTemp"))
        assertTrue(script.contains("TMP = \$processTemp"))
        assertTrue(script.contains("TMPDIR = \$processTemp"))
        assertTrue(script.contains("[SmokeSecretMarkerScanner]::ContainsFile(\$entry.FullName, \$markerBytes)"))
        assertFalse(script.contains("[IO.File]::ReadAllBytes"))
        assertFalse(script.contains("-Filter '*.log'"))
    }

    @Test
    fun `packaged scanner executes clean control without a false positive`() {
        withScannerRoot { root ->
            Files.writeString(root.resolve("clean.txt"), "ordinary packaged smoke output")

            val result = runProductionScanner(root, "task17-clean-marker-1701")

            assertEquals(0, result.exitCode, result.output)
        }
    }

    @Test
    fun `packaged scanner rejects a canary crossing the 64 KiB chunk boundary`() {
        withScannerRoot { root ->
            val marker = "task17-packaged-secret-canary-1701"
            val markerBytes = marker.toByteArray(StandardCharsets.UTF_8)
            val artifact = root.resolve("chunk-boundary.bin")
            Files.write(artifact, ByteArray(65_536 - 3) { 'x'.code.toByte() })
            Files.write(artifact, markerBytes, StandardOpenOption.APPEND)
            Files.write(artifact, byteArrayOf('z'.code.toByte()), StandardOpenOption.APPEND)

            val result = runProductionScanner(root, marker)

            assertNotEquals(0, result.exitCode, "infected control unexpectedly passed")
            assertTrue(result.output.contains("Secret marker detected in packaged smoke artifacts."))
            assertFalse(result.output.contains(marker), "scanner diagnostics must not echo the marker")
        }
    }

    private fun runProductionScanner(root: Path, marker: String): ScanResult {
        val script = Path.of("..", "scripts", "smoke-packaged-distribution.ps1")
            .toAbsolutePath()
            .normalize()
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val powershell = Path.of(
            systemRoot,
            "System32",
            "WindowsPowerShell",
            "v1.0",
            "powershell.exe",
        ).toString()
        val invokeScanner = """
            ${'$'}source = [IO.File]::ReadAllText(${'$'}env:HUITAI_SCANNER_SCRIPT, [Text.Encoding]::UTF8)
            ${'$'}tokens = ${'$'}null
            ${'$'}parseErrors = ${'$'}null
            ${'$'}ast = [System.Management.Automation.Language.Parser]::ParseInput(
                ${'$'}source,
                [ref]${'$'}tokens,
                [ref]${'$'}parseErrors
            )
            if (${'$'}parseErrors.Count -gt 0) { throw 'Packaged smoke script failed to parse.' }
            ${'$'}definition = ${'$'}ast.FindAll({
                param(${'$'}node)
                ${'$'}node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
                    ${'$'}node.Name -eq 'Assert-NoSecretMarkerInTree'
            }, ${'$'}true) | Select-Object -First 1
            if (${'$'}null -eq ${'$'}definition) { throw 'Packaged secret scanner function is missing.' }
            Invoke-Expression ${'$'}definition.Extent.Text
            Assert-NoSecretMarkerInTree `
                -Root ${'$'}env:HUITAI_SCANNER_ROOT `
                -Marker ${'$'}env:HUITAI_SCANNER_MARKER
        """.trimIndent()
        val process = ProcessBuilder(
            powershell,
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            invokeScanner,
        ).redirectErrorStream(true).apply {
            environment()["HUITAI_SCANNER_SCRIPT"] = script.toString()
            environment()["HUITAI_SCANNER_ROOT"] = root.toAbsolutePath().normalize().toString()
            environment()["HUITAI_SCANNER_MARKER"] = marker
        }.start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("packaged secret scanner timed out")
        }
        return ScanResult(process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private fun withScannerRoot(block: (Path) -> Unit) {
        val root = Path.of(
            "build",
            "tmp",
            "task17-secret-scanner",
            UUID.randomUUID().toString(),
        ).toAbsolutePath().normalize()
        Files.createDirectories(root)
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private data class ScanResult(val exitCode: Int, val output: String)
}
