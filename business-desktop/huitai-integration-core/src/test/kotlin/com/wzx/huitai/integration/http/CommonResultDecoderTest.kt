package com.wzx.huitai.integration.http

import com.wzx.huitai.action.model.ActionErrorCode
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class CommonResultDecoderTest {
    private val decoder = CommonResultDecoder()

    @Test
    fun `code 200 decodes a successful CommonResult`() {
        val response = decoder.decode(
            httpStatus = 200,
            contentType = "application/json; charset=utf-8",
            body = jsonBody(
                """{"code":200,"msg":"success","data":{"caseId":"case-1"}}""",
            ),
        )

        val success = assertIs<HuitaiResponse.Success>(response)
        assertEquals("200", success.result.code)
        assertEquals("success", success.result.message)
        assertEquals(
            "case-1",
            success.result.data?.jsonObject?.get("caseId")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `structured business failure retains remote code and message`() {
        val response = decoder.decode(
            httpStatus = 200,
            contentType = "application/json",
            body = jsonBody(
                """{"code":500123,"msg":"form validation failed","data":null}""",
            ),
        )

        val failure = assertIs<HuitaiResponse.Failure>(response)
        assertEquals(ActionErrorCode.REMOTE_REQUEST_FAILED, failure.errorCode)
        assertEquals("500123", failure.remoteCode)
        assertEquals("form validation failed", failure.remoteMessage)
    }

    @Test
    fun `empty successful response is a protocol failure`() {
        val response = decoder.decode(
            httpStatus = 200,
            contentType = "application/json",
            body = byteArrayOf(),
        )

        val failure = assertIs<HuitaiResponse.Failure>(response)
        assertEquals(ActionErrorCode.PROTOCOL_ERROR, failure.errorCode)
        assertNull(failure.remoteCode)
        assertNull(failure.remoteMessage)
    }

    @Test
    fun `JSON error under blob content type is decoded as a structured failure`() {
        val response = decoder.decode(
            httpStatus = 200,
            contentType = "application/octet-stream",
            body = jsonBody(
                """{"code":500456,"msg":"export failed","data":null}""",
            ),
        )

        val failure = assertIs<HuitaiResponse.Failure>(response)
        assertEquals(ActionErrorCode.REMOTE_REQUEST_FAILED, failure.errorCode)
        assertEquals("500456", failure.remoteCode)
        assertEquals("export failed", failure.remoteMessage)
    }

    @Test
    fun `true binary response preserves bytes and content type`() {
        val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00)

        val response = decoder.decode(
            httpStatus = 200,
            contentType = "application/octet-stream",
            body = bytes,
        )

        val binary = assertIs<HuitaiResponse.Binary>(response)
        assertEquals("application/octet-stream", binary.contentType)
        assertContentEquals(bytes, binary.body)
    }

    @Test
    fun `binary response owns its input and returns defensive body copies`() {
        val source = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val binary = HuitaiResponse.Binary(
            contentType = "application/octet-stream",
            body = source,
        )

        source[0] = 0x00
        assertContentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04), binary.body)

        val exposed = binary.body
        exposed[1] = 0x00
        assertContentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04), binary.body)
    }

    @Test
    fun `decoder binary result remains immutable after source and getter mutation`() {
        val source = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val binary = assertIs<HuitaiResponse.Binary>(
            decoder.decode(
                httpStatus = 200,
                contentType = "Application/Octet-Stream; charset=binary",
                body = source,
            ),
        )

        source[0] = 0x00
        binary.body[1] = 0x00

        assertContentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04), binary.body)
    }

    @Test
    fun `malformed JSON response is a protocol failure`() {
        val response = decoder.decode(
            httpStatus = 200,
            contentType = "application/json",
            body = "{broken".encodeToByteArray(),
        )

        assertEquals(
            ActionErrorCode.PROTOCOL_ERROR,
            assertIs<HuitaiResponse.Failure>(response).errorCode,
        )
    }

    @Test
    fun `JSON-shaped malformed blob is a protocol failure instead of binary`() {
        listOf("  {broken", "\n[broken").forEach { malformed ->
            val response = decoder.decode(
                httpStatus = 200,
                contentType = "application/octet-stream",
                body = malformed.encodeToByteArray(),
            )

            assertEquals(
                ActionErrorCode.PROTOCOL_ERROR,
                assertIs<HuitaiResponse.Failure>(response, malformed).errorCode,
            )
        }
    }

    @Test
    fun `response model strings redact data messages and binary body`() {
        val secret = "top-secret-token"
        val result = CommonResult(
            code = "200",
            message = "success",
            data = Json.parseToJsonElement("""{"accessToken":"$secret"}"""),
        )
        val responses = listOf(
            result.toString(),
            HuitaiResponse.Success(result).toString(),
            HuitaiResponse.Failure(
                errorCode = ActionErrorCode.REMOTE_REQUEST_FAILED,
                remoteCode = "500123",
                remoteMessage = secret,
            ).toString(),
            HuitaiResponse.Binary(
                contentType = "application/octet-stream",
                body = secret.encodeToByteArray(),
            ).toString(),
        )

        responses.forEach { rendered ->
            assertFalse(secret in rendered, rendered)
        }
    }

    @Test
    fun `membership expiry envelope maps to MEMBERSHIP_EXPIRED`() {
        val response = decoder.decode(
            httpStatus = 200,
            contentType = "application/json",
            body = jsonBody(
                """{"code":1002010000,"msg":"membership expired","data":null}""",
            ),
        )

        val failure = assertIs<HuitaiResponse.Failure>(response)
        assertEquals(ActionErrorCode.MEMBERSHIP_EXPIRED, failure.errorCode)
        assertEquals("1002010000", failure.remoteCode)
        assertEquals("membership expired", failure.remoteMessage)
    }

    @Test
    fun `HTTP 401 and 499 map to AUTH_EXPIRED`() {
        listOf(401, 499).forEach { httpStatus ->
            val response = decoder.decode(
                httpStatus = httpStatus,
                contentType = "text/plain",
                body = "authentication failed".encodeToByteArray(),
            )

            val failure = assertIs<HuitaiResponse.Failure>(response, "HTTP $httpStatus")
            assertEquals(ActionErrorCode.AUTH_EXPIRED, failure.errorCode, "HTTP $httpStatus")
        }
    }

    @Test
    fun `envelope 401 and 499 map to AUTH_EXPIRED`() {
        listOf(401, 499).forEach { envelopeCode ->
            val response = decoder.decode(
                httpStatus = 200,
                contentType = "application/json",
                body = jsonBody(
                    """{"code":$envelopeCode,"msg":"authentication expired","data":null}""",
                ),
            )

            val failure = assertIs<HuitaiResponse.Failure>(response, "envelope $envelopeCode")
            assertEquals(ActionErrorCode.AUTH_EXPIRED, failure.errorCode, "envelope $envelopeCode")
            assertEquals(envelopeCode.toString(), failure.remoteCode)
            assertEquals("authentication expired", failure.remoteMessage)
        }
    }

    private fun jsonBody(value: String): ByteArray = value.encodeToByteArray()
}
