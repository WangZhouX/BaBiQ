package com.wzx.huitai.security.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditRedactorTest {
    @Test
    fun `递归脱敏凭据文件内容和自定义敏感字段但保留普通key与MONKEY`() {
        val secret = "never-store-this-value"
        val redactor = AuditRedactor(sensitiveFieldIds = setOf("identityNumber"))
        val source = buildJsonObject {
            put("key", "ordinary-business-key")
            put("MONKEY", "ordinary-monkey")
            put("apiKey", secret)
            put("refresh_token", secret)
            put("PASSWORD", secret)
            put("nested", buildJsonArray {
                add(buildJsonObject {
                    put("private-key", secret)
                    put("fileContent", secret)
                    put("IdentityNumber", secret)
                })
            })
        }

        val redacted = redactor.redact(source)
        val nested = redacted["nested"]!!.jsonArray[0].jsonObject

        assertEquals("ordinary-business-key", redacted["key"]!!.jsonPrimitive.content)
        assertEquals("ordinary-monkey", redacted["MONKEY"]!!.jsonPrimitive.content)
        listOf(redacted["apiKey"], redacted["refresh_token"], redacted["PASSWORD"], nested["private-key"],
            nested["fileContent"], nested["IdentityNumber"]).forEach {
            assertEquals(AuditRedactor.REDACTED, it!!.jsonPrimitive.content)
        }
        assertFalse(secret in redacted.toString())
        assertFalse(secret in redactor.toString())
    }

    @Test
    fun `深度和输出字节预算到达边界时安全截断且不泄漏尾部`() {
        val secretTail = "sensitive-tail-value"
        val redactor = AuditRedactor(maxDepth = 3, maxOutputBytes = 256)
        val deep = buildJsonObject {
            put("level1", buildJsonObject {
                put("level2", buildJsonObject {
                    put("level3", buildJsonObject { put("value", secretTail) })
                })
            })
            put("large", "x".repeat(2_000) + secretTail)
        }

        val redacted = redactor.redact(deep)
        val bytes = redacted.toString().toByteArray(StandardCharsets.UTF_8).size

        assertTrue(bytes <= 256)
        assertFalse(secretTail in redacted.toString())
        assertTrue(AuditRedactor.TRUNCATED in redacted.toString())
    }

    @Test
    fun `数组大小也受预算约束且不会触发无界输出`() {
        val redactor = AuditRedactor(maxDepth = 8, maxOutputBytes = 512)
        val source = buildJsonObject {
            put("items", JsonArray((1..10_000).map { JsonPrimitive("item-$it") }))
        }

        val redacted = redactor.redact(source)

        assertTrue(redacted.toString().toByteArray(StandardCharsets.UTF_8).size <= 512)
        assertTrue(AuditRedactor.TRUNCATED in redacted.toString())
    }
}
