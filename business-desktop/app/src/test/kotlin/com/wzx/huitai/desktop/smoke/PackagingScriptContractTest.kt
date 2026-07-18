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
}
