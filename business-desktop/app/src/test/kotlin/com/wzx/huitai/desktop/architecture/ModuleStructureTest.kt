package com.wzx.huitai.desktop.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModuleStructureTest {
    @Test
    fun `settings contains exactly the approved modules`() {
        val settings = Path.of("..", "settings.gradle.kts").toFile().readText()
        assertEquals(APPROVED_MODULES, includedModules(settings))
    }

    @Test
    fun `module parsing ignores comments and detects numeric or underscore names`() {
        val settings = """
            include(
                ":app",
                ":module2",
                ":module_name",
            )
            // ":presentation-core" ":application-action-core" ":agent-client-core"
            // ":huitai-integration-core" ":security-audit-core" ":framework-demo"
        """.trimIndent()

        assertEquals(setOf("app", "module2", "module_name"), includedModules(settings))
    }

    @Test
    fun `retired OA integration module has no build or source authority`() {
        val projectRoot = Path.of("..").toAbsolutePath().normalize()
        val retiredModule = projectRoot.resolve("huitai-integration-core")
        assertFalse(Files.exists(retiredModule.resolve("build.gradle.kts")))
        val retiredSources = retiredModule.resolve("src")
        assertFalse(
            Files.exists(retiredSources) &&
                Files.walk(retiredSources).use { sources ->
                    sources.anyMatch(Files::isRegularFile)
                },
        )
        assertFalse(
            Files.readString(projectRoot.resolve("app/build.gradle.kts"))
                .contains("huitai-integration-core"),
        )
    }

    private fun includedModules(settings: String): Set<String> {
        val source = settings
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")
        return Regex("\\binclude\\s*\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL)
            .findAll(source)
            .flatMap { include ->
                Regex("[\\\"']\\s*:([^\\\"']+)[\\\"']")
                    .findAll(include.groupValues[1])
                    .map { it.groupValues[1] }
            }
            .toSet()
    }

    private companion object {
        val APPROVED_MODULES = setOf(
            "app", "presentation-core", "application-action-core",
            "agent-client-core",
            "security-audit-core", "framework-demo",
        )
    }
}
