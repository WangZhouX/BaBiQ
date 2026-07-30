package com.wzx.huitai.desktop.smoke

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackagedAuthenticatedSmokeScriptContractTest {
    @Test
    fun `authenticated smoke restores OA base URL and always removes its validated temp root`() {
        val script = Path.of("..", "scripts", "smoke-packaged-distribution.ps1").toFile().readText()

        assertTrue(script.contains("HUITAI_OA_BASE_URL = ''"))
        assertTrue(script.contains("function Remove-SmokeTemporaryRoot"))
        val scan = script.indexOf("Assert-NoSecretMarkerInTree -Root \$temporaryRoot")
        val cleanup = script.lastIndexOf("Remove-SmokeTemporaryRoot -Root \$temporaryRoot")
        assertTrue(scan >= 0)
        assertTrue(cleanup > scan, "temp cleanup must be attempted after the independent canary scan block")
    }

    @Test
    fun `authenticated process identity is retained so orphan descendants can be stopped`() {
        val script = Path.of("..", "scripts", "smoke-packaged-distribution.ps1").toFile().readText()

        assertFalse(script.contains("\$authenticatedProcess = \$null\n\n    Write-Host"))
        assertTrue(
            script.contains(
                "if (\$null -ne \$ownedRootProcess) {\n                \$descendants += @(",
            ),
        )
    }

    @Test
    fun `fake OA requires the exact double MD5 password and rejects unknown routes`() {
        val script = Path.of("..", "scripts", "packaged-authenticated-fake-oa.ps1").toFile().readText()

        assertTrue(script.contains("function Get-DoubleMd5"))
        assertTrue(script.contains("\$Value + 'huitaisystem'"))
        assertTrue(script.contains("\$loginBody.password -ne (Get-DoubleMd5 -Value \$password)"))
        assertTrue(script.contains("default { throw 'Unexpected fake OA route.' }"))
    }
}
