package com.wzx.huitai.integration.http

import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ReconciliationPolicy
import java.util.Collections

/** 不包含任何业务 endpoint 常量的汇泰请求元数据。 */
class HuitaiRequest(
    val method: String,
    val relativePath: String,
    headers: Map<String, String>,
    body: ByteArray,
    val replayPolicy: ActionReplayPolicy,
    val executionId: String?,
    val idempotencyHeaderName: String?,
    val reconciliationPolicy: ReconciliationPolicy,
) {
    val headers: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(headers))

    private val bodyBytes = body.copyOf()

    val body: ByteArray
        get() = bodyBytes.copyOf()

    init {
        require(METHOD_TOKEN.matches(method)) { "method must be a valid HTTP token" }
        require(relativePath.startsWith('/') && !relativePath.startsWith("//")) {
            "relativePath must start with exactly one /"
        }
        require('#' !in relativePath) { "relativePath must not contain a fragment" }
        headers.forEach { (name, value) ->
            require(METHOD_TOKEN.matches(name)) { "header name must be a valid HTTP token" }
            require('\r' !in value && '\n' !in value) { "header value must not contain CR or LF" }
        }
    }

    internal fun hasAttachedIdempotencyKey(): Boolean {
        val id = executionId?.takeIf { it.isNotBlank() } ?: return false
        val headerName = idempotencyHeaderName?.takeIf { it.isNotBlank() } ?: return false
        val matchingValues = headers.entries
            .filter { (name) -> name.equals(headerName, ignoreCase = true) }
            .map { it.value }
        return matchingValues.isNotEmpty() && matchingValues.all { it == id }
    }

    override fun toString(): String {
        val safePath = relativePath.substringBefore('?')
        val querySummary = if ('?' in relativePath) "?query=[REDACTED]" else ""
        return "HuitaiRequest(method=$method, relativePath=$safePath$querySummary, " +
            "headers=[REDACTED], body=[REDACTED:${bodyBytes.size} bytes], " +
            "replayPolicy=$replayPolicy, executionId=[REDACTED], " +
            "idempotencyHeaderName=[REDACTED], reconciliationPolicy=$reconciliationPolicy)"
    }

    private companion object {
        val METHOD_TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    }
}
