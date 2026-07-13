package com.wzx.huitai.desktop.architecture

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleStructureTest {
    @Test
    fun `settings contains exactly the approved modules`() {
        val settings = Path.of("..", "settings.gradle.kts").toFile().readText()
        val actual = Regex("\\\":([a-z-]+)\\\"")
            .findAll(settings)
            .map { it.groupValues[1] }
            .toSet()
        val expected = setOf(
            "app", "presentation-core", "application-action-core",
            "agent-client-core", "huitai-integration-core",
            "security-audit-core", "framework-demo",
        )
        assertEquals(expected, actual)
    }
}
