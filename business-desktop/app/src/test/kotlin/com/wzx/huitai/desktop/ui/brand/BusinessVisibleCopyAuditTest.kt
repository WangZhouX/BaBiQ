package com.wzx.huitai.desktop.ui.brand

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessVisibleCopyAuditTest {
    @Test
    fun `main sources do not expose legacy desktop brands or agent copy`() {
        val mainRoot = Path.of("src", "main").toAbsolutePath().normalize()
        assertTrue("找不到待审计的 main 源码目录：$mainRoot", Files.isDirectory(mainRoot))
        val forbiddenExactCopy = listOf(
            "汇泰业务桌面 Agent",
            "汇泰业务桌面端",
            "业务 Agent",
            "告诉 Agent 需要整理或修改的内容",
            "Agent 建议",
        )
        val findings = textFilesUnder(mainRoot).flatMap { file ->
            val text = file.readText()
            val visibleTextSegments = if (file.extension.equals("kt", ignoreCase = true)) {
                extractRegularStringLiterals(text)
            } else {
                listOf(text)
            }
            buildList {
                visibleTextSegments.forEach { segment ->
                    forbiddenExactCopy.filter(segment::contains).forEach { copy ->
                        add("${file.toString().replace('\\', '/')}: $copy")
                    }
                }
                visibleTextSegments
                    .filter { it == "Agent" }
                    .forEach { copy -> add("${file.toString().replace('\\', '/')}: \"$copy\"") }
            }
        }

        assertTrue(
            "发现仍会暴露给用户的旧品牌或 Agent 文案：\n${findings.joinToString("\n")}",
            findings.isEmpty(),
        )
    }

    private fun textFilesUnder(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter(Path::isRegularFile)
            .filter { it.extension.lowercase() in TEXT_EXTENSIONS }
            .sorted()
            .toList()
    }

    private fun extractRegularStringLiterals(source: String): List<String> =
        REGULAR_STRING.findAll(source)
            .map { match -> match.value.removeSurrounding("\"") }
            .toList()

    private companion object {
        val TEXT_EXTENSIONS = setOf(
            "kt",
            "xml",
            "properties",
            "json",
            "txt",
            "conf",
            "yaml",
            "yml",
            "html",
            "css",
            "js",
        )
        val REGULAR_STRING = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
    }
}
