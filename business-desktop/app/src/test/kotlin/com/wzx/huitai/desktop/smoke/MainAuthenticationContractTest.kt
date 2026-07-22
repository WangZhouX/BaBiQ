package com.wzx.huitai.desktop.smoke

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class MainAuthenticationContractTest {
    @Test
    fun `main does not bypass authenticated provider readiness gate`() {
        val source = Files.readString(
            Path.of("src", "main", "kotlin", "com", "wzx", "huitai", "desktop", "Main.kt"),
        )

        assertFalse(source.contains("conversationController.refreshProviders()"))
    }
}
