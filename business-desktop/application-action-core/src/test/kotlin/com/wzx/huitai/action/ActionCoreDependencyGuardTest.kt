package com.wzx.huitai.action

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertTrue

class ActionCoreDependencyGuardTest {
    @Test
    fun `动作核心源码不得依赖界面传输数据库或安全适配器`() {
        val sourceRoot = locateSourceRoot()
        val forbidden = listOf(
            "androidx.compose",
            "io.ktor",
            "java.sql",
            "org.sqlite",
            "org.springframework",
            "com.wzx.huitai.security",
        )
        val violations = mutableListOf<String>()
        Files.walk(sourceRoot).use { paths ->
            paths.iterator().asSequence()
                .filter { it.isRegularFile() && it.extension == "kt" }
                .forEach { path ->
                Files.readAllLines(path).forEach { line ->
                    if (line.trimStart().startsWith("import ")) {
                        forbidden.filter(line::contains).forEach { violations += "$path -> $it" }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), "application-action-core 出现禁止依赖: $violations")
    }

    private fun locateSourceRoot(): Path {
        val candidates = listOf(
            Path.of("src", "main", "kotlin"),
            Path.of("application-action-core", "src", "main", "kotlin"),
            Path.of("business-desktop", "application-action-core", "src", "main", "kotlin"),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("找不到 application-action-core 源码目录")
    }
}
