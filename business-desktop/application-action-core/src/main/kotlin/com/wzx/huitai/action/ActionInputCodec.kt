package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** JSON 输入解码结果。 */
sealed interface ActionInputDecodeResult<out I : Any> {
    /** 输入已成功转换为强类型值。 */
    data class Success<I : Any>(val value: I) : ActionInputDecodeResult<I> {
        override fun toString(): String = "ActionInputDecodeResult.Success(value=[REDACTED])"
    }

    /** 输入未通过结构或业务字段校验。 */
    data class Failure(val error: ActionError) : ActionInputDecodeResult<Nothing> {
        init {
            require(error.code == ActionErrorCode.VALIDATION_FAILED) {
                "ActionInputDecodeResult.Failure 仅允许 VALIDATION_FAILED"
            }
        }

        override fun toString(): String = "ActionInputDecodeResult.Failure(errorCode=${error.code})"
    }
}

/**
 * 将 JSON 输入转换为动作专属强类型输入。
 *
 * @param I 解码后的动作输入类型。
 */
fun interface ActionInputCodec<I : Any> {
    /** 解码并校验输入，不向调用方抛出原始序列化异常。 */
    fun decode(input: JsonObject): ActionInputDecodeResult<I>
}

/**
 * 将强类型动作输出编码为 JSON 安全值。
 *
 * @param O 动作成功输出类型。
 */
fun interface ActionOutputCodec<O : Any> {
    /** 编码输出，不得使用 `toString()` 代替结构化 JSON。 */
    fun encode(output: O): JsonElement
}
