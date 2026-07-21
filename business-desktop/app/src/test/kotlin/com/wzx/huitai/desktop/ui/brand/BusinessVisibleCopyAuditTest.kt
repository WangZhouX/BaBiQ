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
    fun `kotlin scanner flags legacy copy in a regular string`() {
        assertTrue(auditKotlinSource("val title = \"汇泰业务桌面端\"").contains("汇泰业务桌面端"))
    }

    @Test
    fun `kotlin scanner flags visible agent copy inside a raw string template`() {
        val source = "val title = \"\"\"业务 \${\"Agent\"}\"\"\""

        assertTrue(auditKotlinSource(source).contains("业务 Agent"))
    }

    @Test
    fun `kotlin scanner flags a legacy brand assembled from adjacent constants`() {
        val source = "val title = \"汇泰业务\" + \"桌面端\""

        assertTrue(auditKotlinSource(source).contains("汇泰业务桌面端"))
    }

    @Test
    fun `kotlin scanner ignores quoted legacy copy in comments and runtime technical errors`() {
        val source = """
            /** 文档示例："汇泰业务桌面端"、"业务 Agent"。 */
            // "告诉 Agent 需要整理或修改的内容"
            /* 外层 "Agent 建议"
               /* 嵌套 "汇泰业务桌面 Agent" */
            */
            val quote = '\"'
            val slash = '/'
            val technicalError = "Agent connection changed during identity registration"
            val label = "小律"
        """.trimIndent()

        assertTrue(auditKotlinSource(source).isEmpty())
    }

    @Test
    fun `main sources do not expose legacy desktop brands or agent copy`() {
        val mainRoot = Path.of("src", "main").toAbsolutePath().normalize()
        assertTrue("找不到待审计的 main 源码目录：$mainRoot", Files.isDirectory(mainRoot))
        val findings = textFilesUnder(mainRoot).flatMap { file ->
            val text = file.readText()
            val copyFindings = if (file.extension.equals("kt", ignoreCase = true)) {
                auditKotlinSource(text)
            } else {
                auditVisibleSegments(listOf(text))
            }
            copyFindings.map { finding ->
                "${file.toString().replace('\\', '/')}: $finding"
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

    private fun auditKotlinSource(source: String): List<String> =
        auditVisibleSegments(KotlinVisibleStringScanner.scan(source))

    private fun auditVisibleSegments(segments: List<String>): List<String> = buildList {
        segments.forEach { segment ->
            val normalized = normalizeVisibleCopy(segment)
            FORBIDDEN_VISIBLE_COPY.forEach { (pattern, finding) ->
                if (pattern.containsMatchIn(normalized)) add(finding)
            }
            if (normalized == "Agent") add("\"Agent\"")
        }
    }.distinct()

    private fun normalizeVisibleCopy(copy: String): String = buildString(copy.length) {
        var pendingSpace = false
        copy.forEach { character ->
            val type = Character.getType(character)
            val separator = character.isWhitespace() || type in PUNCTUATION_OR_SYMBOL_TYPES
            if (separator) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }.trim()

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
        val FORBIDDEN_VISIBLE_COPY = listOf(
            Regex("汇泰业务桌面\\s*Agent") to "汇泰业务桌面 Agent",
            Regex("汇泰业务桌面端") to "汇泰业务桌面端",
            Regex("汇泰业务") to "汇泰业务",
            Regex("业务\\s*Agent") to "业务 Agent",
            Regex("告诉\\s*Agent\\s*需要整理或修改的内容") to "告诉 Agent 需要整理或修改的内容",
            Regex("Agent\\s*建议") to "Agent 建议",
        )
        val PUNCTUATION_OR_SYMBOL_TYPES = setOf(
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
        )
    }
}

/**
 * 面向可见文案审计的轻量 Kotlin lexer。
 *
 * 它只识别字符串常量、模板中的常量字符串和由 `+` 连接的相邻常量；注释、字符常量及其他
 * Kotlin token 都作为边界处理，因此不会把 KDoc 或运行时代码标识误认为界面文案。
 */
private object KotlinVisibleStringScanner {
    fun scan(source: String): List<String> {
        val lexemes = Lexer(source).lex()
        return buildList {
            var index = 0
            while (index < lexemes.size) {
                val literal = lexemes[index] as? Lexeme.Literal
                if (literal == null) {
                    index += 1
                    continue
                }
                add(literal.value)
                var combined = literal.value
                var cursor = index
                while (
                    cursor + 2 < lexemes.size &&
                    lexemes[cursor + 1] == Lexeme.Plus &&
                    lexemes[cursor + 2] is Lexeme.Literal
                ) {
                    combined += (lexemes[cursor + 2] as Lexeme.Literal).value
                    add(combined)
                    cursor += 2
                }
                index = if (cursor == index) index + 1 else cursor + 1
            }
        }.distinct()
    }

    private sealed interface Lexeme {
        data class Literal(val value: String) : Lexeme
        data object Plus : Lexeme
        data object Boundary : Lexeme
    }

    private class Lexer(private val source: String) {
        private var index = 0

        fun lex(): List<Lexeme> = buildList {
            while (index < source.length) {
                when {
                    source[index].isWhitespace() -> index += 1
                    source.startsWith("//", index) -> skipLineComment()
                    source.startsWith("/*", index) -> skipBlockComment()
                    source.startsWith("\"\"\"", index) -> add(parseString(raw = true))
                    source[index] == '"' -> add(parseString(raw = false))
                    source[index] == '\'' -> {
                        skipCharacterLiteral()
                        addBoundary()
                    }
                    source[index] == '+' -> {
                        index += 1
                        add(Lexeme.Plus)
                    }
                    else -> {
                        skipOtherToken()
                        addBoundary()
                    }
                }
            }
        }

        private fun MutableList<Lexeme>.addBoundary() {
            if (lastOrNull() != Lexeme.Boundary) add(Lexeme.Boundary)
        }

        private fun parseString(raw: Boolean): Lexeme.Literal {
            index += if (raw) 3 else 1
            val value = StringBuilder()
            while (index < source.length) {
                if (raw && source.startsWith("\"\"\"", index)) {
                    index += 3
                    break
                }
                if (!raw && source[index] == '"') {
                    index += 1
                    break
                }
                if (!raw && source[index] == '\\') {
                    appendEscapedCharacter(value)
                    continue
                }
                if (source[index] == '$') {
                    if (appendConstantTemplate(value)) continue
                    if (index + 1 < source.length && source[index + 1].isKotlinIdentifierStart()) {
                        index += 2
                        while (index < source.length && source[index].isKotlinIdentifierPart()) index += 1
                        value.append(' ')
                        continue
                    }
                }
                value.append(source[index])
                index += 1
            }
            return Lexeme.Literal(value.toString())
        }

        private fun appendConstantTemplate(target: StringBuilder): Boolean {
            if (!source.startsWith("${'$'}{", index)) return false
            val expressionStart = index + 2
            val expressionEnd = findTemplateExpressionEnd(expressionStart) ?: return false
            val expression = source.substring(expressionStart, expressionEnd)
            val constant = constantStringExpression(expression)
            target.append(constant ?: " ")
            index = expressionEnd + 1
            return true
        }

        private fun constantStringExpression(expression: String): String? {
            val tokens = Lexer(expression).lex()
            if (tokens.isEmpty() || tokens.first() !is Lexeme.Literal) return null
            val value = StringBuilder((tokens.first() as Lexeme.Literal).value)
            var cursor = 1
            while (cursor < tokens.size) {
                if (tokens[cursor] != Lexeme.Plus || cursor + 1 >= tokens.size) return null
                val literal = tokens[cursor + 1] as? Lexeme.Literal ?: return null
                value.append(literal.value)
                cursor += 2
            }
            return value.toString()
        }

        private fun findTemplateExpressionEnd(start: Int): Int? {
            var cursor = start
            var depth = 1
            while (cursor < source.length) {
                when {
                    source.startsWith("//", cursor) -> cursor = skipLineCommentAt(cursor)
                    source.startsWith("/*", cursor) -> cursor = skipBlockCommentAt(cursor)
                    source.startsWith("\"\"\"", cursor) -> cursor = skipQuotedAt(cursor, raw = true)
                    source[cursor] == '"' -> cursor = skipQuotedAt(cursor, raw = false)
                    source[cursor] == '\'' -> cursor = skipCharacterAt(cursor)
                    source[cursor] == '{' -> {
                        depth += 1
                        cursor += 1
                    }
                    source[cursor] == '}' -> {
                        depth -= 1
                        if (depth == 0) return cursor
                        cursor += 1
                    }
                    else -> cursor += 1
                }
            }
            return null
        }

        private fun appendEscapedCharacter(target: StringBuilder) {
            if (index + 1 >= source.length) {
                index += 1
                return
            }
            val escaped = source[index + 1]
            if (escaped == 'u' && index + 5 < source.length) {
                val hex = source.substring(index + 2, index + 6)
                val decoded = hex.toIntOrNull(16)
                if (decoded != null) {
                    target.append(decoded.toChar())
                    index += 6
                    return
                }
            }
            target.append(
                when (escaped) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'b' -> '\b'
                    else -> escaped
                },
            )
            index += 2
        }

        private fun skipLineComment() {
            index = skipLineCommentAt(index)
        }

        private fun skipBlockComment() {
            index = skipBlockCommentAt(index)
        }

        private fun skipCharacterLiteral() {
            index = skipCharacterAt(index)
        }

        private fun skipOtherToken() {
            if (source[index].isKotlinIdentifierStart()) {
                index += 1
                while (index < source.length && source[index].isKotlinIdentifierPart()) index += 1
            } else {
                index += 1
            }
        }

        private fun skipLineCommentAt(start: Int): Int {
            var cursor = start + 2
            while (cursor < source.length && source[cursor] != '\n' && source[cursor] != '\r') cursor += 1
            return cursor
        }

        private fun skipBlockCommentAt(start: Int): Int {
            var cursor = start + 2
            var depth = 1
            while (cursor < source.length && depth > 0) {
                when {
                    source.startsWith("/*", cursor) -> {
                        depth += 1
                        cursor += 2
                    }
                    source.startsWith("*/", cursor) -> {
                        depth -= 1
                        cursor += 2
                    }
                    else -> cursor += 1
                }
            }
            return cursor
        }

        private fun skipQuotedAt(start: Int, raw: Boolean): Int {
            var cursor = start + if (raw) 3 else 1
            while (cursor < source.length) {
                if (raw && source.startsWith("\"\"\"", cursor)) return cursor + 3
                if (!raw && source[cursor] == '\\') {
                    cursor = (cursor + 2).coerceAtMost(source.length)
                } else if (!raw && source[cursor] == '"') {
                    return cursor + 1
                } else {
                    cursor += 1
                }
            }
            return cursor
        }

        private fun skipCharacterAt(start: Int): Int {
            var cursor = start + 1
            while (cursor < source.length) {
                if (source[cursor] == '\\') {
                    cursor = (cursor + 2).coerceAtMost(source.length)
                } else if (source[cursor] == '\'') {
                    return cursor + 1
                } else {
                    cursor += 1
                }
            }
            return cursor
        }

        private fun Char.isKotlinIdentifierStart(): Boolean = this == '_' || this.isLetter()

        private fun Char.isKotlinIdentifierPart(): Boolean = isKotlinIdentifierStart() || isDigit()
    }
}
