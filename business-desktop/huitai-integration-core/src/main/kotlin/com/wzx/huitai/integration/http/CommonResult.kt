package com.wzx.huitai.integration.http

import kotlinx.serialization.json.JsonElement

/** 汇泰接口统一响应 envelope。 */
data class CommonResult(
    val code: String,
    val message: String,
    val data: JsonElement?,
) {
    override fun toString(): String =
        "CommonResult(code=$code, message=[REDACTED], data=[REDACTED])"
}
