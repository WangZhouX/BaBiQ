package com.wzx.huitai.desktop.integration

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BusinessRealBackendTestHarnessTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `runtime audit fails when a required artifact is missing`() {
        val runtime = Files.createDirectories(tempDir.resolve("required-runtime"))
        val missing = runtime.resolve("logs/backend.log")

        assertFailsWith<IllegalStateException> {
            BusinessRealBackendTestHarness.assertOaSecretsAbsent(runtime, missing)
        }
    }

    @Test
    fun `runtime audit recursively scans artifacts not listed by the caller`() {
        val runtime = Files.createDirectories(tempDir.resolve("recursive-runtime"))
        val required = Files.writeString(runtime.resolve("required.log"), "safe")
        Files.createDirectories(runtime.resolve("nested"))
        Files.writeString(
            runtime.resolve("nested/unlisted.bin"),
            "task17-desktop-access-recursive-canary",
        )

        assertFailsWith<IllegalStateException> {
            BusinessRealBackendTestHarness.assertOaSecretsAbsent(runtime, required)
        }
    }
}
