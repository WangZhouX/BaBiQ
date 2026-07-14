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
        return decoder(raw)
    }
}
