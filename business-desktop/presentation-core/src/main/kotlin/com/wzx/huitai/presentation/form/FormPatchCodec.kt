package com.wzx.huitai.presentation.form

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * FormPatch 不可信协议文本的唯一推荐解码入口。
 *
 * 模型 serializer 只能在 JSON parser 构造值之后执行预算校验，因此网络或 Agent 原始文本必须先经过本 codec。
 */
object FormPatchCodec {
    /** 在解析 JSON 前检查 128 KiB UTF-8 上限，再执行冻结模型的自定义 serializer。 */
    fun decode(raw: String): FormPatch = decode(raw) { boundedRaw ->
        Json.decodeFromString<FormPatch>(boundedRaw)
    }

    /** 测试注入点仍强制执行同一 parser 前预算，不能绕过协议边界。 */
    internal fun decode(
        raw: String,
        decoder: (String) -> FormPatch,
    ): FormPatch {
        requireFormPatchRawBudget(raw)
        JsonPreflightScanner(raw).validate()
        return decoder(raw)
    }
}

private const val FORM_PATCH_MAX_RAW_DEPTH = 32

/** 在递归 JSON parser 前，用迭代状态机校验结构深度和对象成员唯一性。 */
private class JsonPreflightScanner(
    private val raw: String,
) {
    private val frames = ArrayDeque<Frame>()
    private var index = 0
    private var rootComplete = false

    fun validate() {
        skipWhitespace()
        while (index < raw.length) {
            if (rootComplete) fail("JSON 根值后存在多余内容")
            if (frames.isEmpty()) {
                consumeValue()
            } else {
                when (val frame = frames.last()) {
                    is ObjectFrame -> consumeObject(frame)
                    is ArrayFrame -> consumeArray(frame)
                }
            }
            skipWhitespace()
        }
        require(rootComplete && frames.isEmpty()) { "JSON 结构不完整" }
    }

    private fun consumeObject(frame: ObjectFrame) {
        when (frame.state) {
            ObjectState.KEY_OR_END -> {
                if (peek('}')) {
                    if (!frame.allowEnd) fail("JSON 对象逗号后缺少成员")
                    closeContainer('}')
                    return
                }
                require(peek('"')) { "JSON 对象成员名必须是字符串" }
                val key = readString()
                require(frame.keys.add(key)) { "JSON 对象存在重复成员" }
                frame.state = ObjectState.COLON
                frame.allowEnd = false
            }
            ObjectState.COLON -> {
                require(consume(':')) { "JSON 对象成员缺少冒号" }
                frame.state = ObjectState.VALUE
            }
            ObjectState.VALUE -> consumeValue()
            ObjectState.COMMA_OR_END -> when {
                consume(',') -> {
                    frame.state = ObjectState.KEY_OR_END
                    frame.allowEnd = false
                }
                consume('}') -> closeContainerAlreadyConsumed()
                else -> fail("JSON 对象成员后缺少逗号或右括号")
            }
        }
    }

    private fun consumeArray(frame: ArrayFrame) {
        when (frame.state) {
            ArrayState.VALUE_OR_END -> {
                if (peek(']')) {
                    if (!frame.allowEnd) fail("JSON 数组逗号后缺少元素")
                    closeContainer(']')
                } else {
                    consumeValue()
                }
            }
            ArrayState.COMMA_OR_END -> when {
                consume(',') -> {
                    frame.state = ArrayState.VALUE_OR_END
                    frame.allowEnd = false
                }
                consume(']') -> closeContainerAlreadyConsumed()
                else -> fail("JSON 数组元素后缺少逗号或右括号")
            }
        }
    }

    private fun consumeValue() {
        if (frames.isNotEmpty()) {
            when (val parent = frames.last()) {
                is ObjectFrame -> require(parent.state == ObjectState.VALUE) { "JSON 对象状态无效" }
                is ArrayFrame -> require(parent.state == ArrayState.VALUE_OR_END) { "JSON 数组状态无效" }
            }
        }
        require(index < raw.length) { "JSON 缺少值" }
        when (raw[index]) {
            '{' -> open(ObjectFrame())
            '[' -> open(ArrayFrame())
            '"' -> {
                readString()
                valueComplete()
            }
            't' -> {
                readLiteral("true")
                valueComplete()
            }
            'f' -> {
                readLiteral("false")
                valueComplete()
            }
            'n' -> {
                readLiteral("null")
                valueComplete()
            }
            '-', in '0'..'9' -> {
                readNumber()
                valueComplete()
            }
            else -> fail("JSON 值格式无效")
        }
    }

    private fun open(frame: Frame) {
        index += 1
        require(frames.size + 1 <= FORM_PATCH_MAX_RAW_DEPTH) { "表单补丁 JSON 深度超限" }
        frames.addLast(frame)
    }

    private fun closeContainer(expected: Char) {
        require(consume(expected)) { "JSON 容器结束符无效" }
        closeContainerAlreadyConsumed()
    }

    private fun closeContainerAlreadyConsumed() {
        frames.removeLast()
        valueComplete()
    }

    private fun valueComplete() {
        if (frames.isEmpty()) {
            rootComplete = true
            return
        }
        when (val parent = frames.last()) {
            is ObjectFrame -> {
                require(parent.state == ObjectState.VALUE) { "JSON 对象值状态无效" }
                parent.state = ObjectState.COMMA_OR_END
            }
            is ArrayFrame -> {
                require(parent.state == ArrayState.VALUE_OR_END) { "JSON 数组值状态无效" }
                parent.state = ArrayState.COMMA_OR_END
            }
        }
    }

    private fun readString(): String {
        require(consume('"')) { "JSON 字符串起始符无效" }
        val decoded = StringBuilder()
        while (index < raw.length) {
            val character = raw[index++]
            when {
                character == '"' -> return decoded.toString()
                character == '\\' -> decoded.append(readEscape())
                character.code < 0x20 -> fail("JSON 字符串包含控制字符")
                else -> decoded.append(character)
            }
        }
        fail("JSON 字符串未闭合")
    }

    private fun readEscape(): Char {
        require(index < raw.length) { "JSON 转义序列不完整" }
        return when (val escaped = raw[index++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> fail("JSON 转义序列无效")
        }
    }

    private fun readUnicodeEscape(): Char {
        require(index + 4 <= raw.length) { "JSON Unicode 转义不完整" }
        var value = 0
        repeat(4) {
            val digit = raw[index++].digitToIntOrNull(16) ?: fail("JSON Unicode 转义无效")
            value = value * 16 + digit
        }
        return value.toChar()
    }

    private fun readLiteral(literal: String) {
        require(raw.regionMatches(index, literal, 0, literal.length)) { "JSON 字面量无效" }
        index += literal.length
    }

    private fun readNumber() {
        if (consume('-')) require(index < raw.length) { "JSON 数字不完整" }
        when {
            consume('0') -> require(index >= raw.length || raw[index] !in '0'..'9') { "JSON 数字前导零无效" }
            index < raw.length && raw[index] in '1'..'9' -> while (index < raw.length && raw[index] in '0'..'9') index += 1
            else -> fail("JSON 数字整数部分无效")
        }
        if (consume('.')) {
            require(index < raw.length && raw[index] in '0'..'9') { "JSON 数字小数部分无效" }
            while (index < raw.length && raw[index] in '0'..'9') index += 1
        }
        if (index < raw.length && raw[index] in charArrayOf('e', 'E')) {
            index += 1
            if (index < raw.length && raw[index] in charArrayOf('+', '-')) index += 1
            require(index < raw.length && raw[index] in '0'..'9') { "JSON 数字指数部分无效" }
            while (index < raw.length && raw[index] in '0'..'9') index += 1
        }
    }

    private fun skipWhitespace() {
        while (index < raw.length && raw[index] in charArrayOf(' ', '\t', '\r', '\n')) index += 1
    }

    private fun consume(expected: Char): Boolean {
        if (!peek(expected)) return false
        index += 1
        return true
    }

    private fun peek(expected: Char): Boolean = index < raw.length && raw[index] == expected

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private sealed interface Frame

    private class ObjectFrame(
        val keys: MutableSet<String> = hashSetOf(),
        var state: ObjectState = ObjectState.KEY_OR_END,
        var allowEnd: Boolean = true,
    ) : Frame

    private class ArrayFrame(
        var state: ArrayState = ArrayState.VALUE_OR_END,
        var allowEnd: Boolean = true,
    ) : Frame

    private enum class ObjectState { KEY_OR_END, COLON, VALUE, COMMA_OR_END }
    private enum class ArrayState { VALUE_OR_END, COMMA_OR_END }
}
