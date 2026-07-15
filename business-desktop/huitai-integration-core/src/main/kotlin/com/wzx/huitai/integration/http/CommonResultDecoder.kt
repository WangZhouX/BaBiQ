package com.wzx.huitai.integration.http

import com.wzx.huitai.action.model.ActionErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** 将 HTTP 响应解码为统一成功、二进制或结构化失败结果。 */
class CommonResultDecoder(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decode(
        httpStatus: Int,
        contentType: String?,
        body: ByteArray,
    ): HuitaiResponse {
        if (httpStatus in AUTH_EXPIRED_CODES) {
            return HuitaiResponse.Failure(ActionErrorCode.AUTH_EXPIRED)
        }
        if (body.isEmpty()) {
            return HuitaiResponse.Failure(ActionErrorCode.PROTOCOL_ERROR)
        }

        val text = body.decodeStrictUtf8()?.trim()
        if (text == null) {
            return if (contentType.isBinaryContentType()) {
                HuitaiResponse.Binary(contentType = contentType, body = body)
            } else {
                HuitaiResponse.Failure(ActionErrorCode.PROTOCOL_ERROR)
            }
        }
        val envelope = decodeEnvelope(text)
        if (envelope != null) {
            return envelope.toResponse()
        }
        if (text.isJsonShaped() || contentType.isJsonContentType()) {
            return HuitaiResponse.Failure(ActionErrorCode.PROTOCOL_ERROR)
        }

        return if (contentType.isBinaryContentType()) {
            HuitaiResponse.Binary(contentType = contentType, body = body.copyOf())
        } else {
            HuitaiResponse.Failure(ActionErrorCode.PROTOCOL_ERROR)
        }
    }

    private fun decodeEnvelope(text: String): CommonResult? {
        if (!text.startsWith('{')) return null

        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return null
        val code = root["code"].asScalarString() ?: return null
        val message = root["msg"].asScalarString() ?: return null
        return CommonResult(
            code = code,
            message = message,
            data = root["data"]?.takeUnless { it is JsonNull },
        )
    }

    private fun CommonResult.toResponse(): HuitaiResponse = when (code) {
        SUCCESS_CODE -> HuitaiResponse.Success(this)
        MEMBERSHIP_EXPIRED_CODE -> failure(ActionErrorCode.MEMBERSHIP_EXPIRED)
        in AUTH_EXPIRED_CODES_AS_TEXT -> failure(ActionErrorCode.AUTH_EXPIRED)
        else -> failure(ActionErrorCode.REMOTE_REQUEST_FAILED)
    }

    private fun CommonResult.failure(errorCode: ActionErrorCode) = HuitaiResponse.Failure(
        errorCode = errorCode,
        remoteCode = code,
        remoteMessage = message,
    )

    private fun kotlinx.serialization.json.JsonElement?.asScalarString(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.jsonPrimitive.contentOrNull
    }

    private fun String?.isBinaryContentType(): Boolean {
        val mediaType = normalizedMediaType()
        return mediaType == "application/octet-stream" ||
            mediaType == "application/pdf" ||
            mediaType == "application/zip" ||
            mediaType?.startsWith("image/") == true ||
            mediaType?.startsWith("audio/") == true ||
            mediaType?.startsWith("video/") == true
    }

    private fun String?.isJsonContentType(): Boolean {
        val mediaType = normalizedMediaType()
        return mediaType == "application/json" || mediaType?.endsWith("+json") == true
    }

    private fun String?.normalizedMediaType(): String? =
        this?.substringBefore(';')?.trim()?.lowercase()

    private fun String.isJsonShaped(): Boolean = startsWith('{') || startsWith('[')

    private fun ByteArray.decodeStrictUtf8(): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    }.getOrNull()

    private companion object {
        const val SUCCESS_CODE = "200"
        const val MEMBERSHIP_EXPIRED_CODE = "1002010000"
        val AUTH_EXPIRED_CODES = setOf(401, 499)
        val AUTH_EXPIRED_CODES_AS_TEXT = AUTH_EXPIRED_CODES.map { it.toString() }.toSet()
    }
}
