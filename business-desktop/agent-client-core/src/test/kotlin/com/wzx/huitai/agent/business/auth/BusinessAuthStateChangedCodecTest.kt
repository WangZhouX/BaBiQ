package com.wzx.huitai.agent.business.auth

import com.wzx.huitai.agent.client.AgentRawNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BusinessAuthStateChangedCodecTest {
    @Test
    fun `decodes strict state notification without requiring identity epoch`() {
        val change = BusinessAuthStateChangedCodec.decode(
            AgentRawNotification(
                method = "business/auth/state-changed",
                params = buildJsonObject {
                    put("authSessionId", "auth-session-1")
                    put("state", "SIGNED_OUT")
                    put("generation", 3)
                    put("businessCode", "BUSINESS_AUTH_EXPIRED")
                    put("accessToken", "must-not-survive")
                },
            ),
        )

        assertEquals("auth-session-1", change.authSessionId)
        assertEquals(BusinessAuthStatus.SIGNED_OUT, change.state)
        assertEquals(3, change.generation)
        assertEquals(BusinessAuthStateChangeCode.AUTH_EXPIRED, change.businessCode)
        assertFalse(change.toString().contains("auth-session-1"))
        assertFalse(change.toString().contains("must-not-survive"))
    }

    @Test
    fun `rejects unknown business code and malformed state payload`() {
        val invalid = listOf(
            stateParams(businessCode = "BUSINESS_FUTURE_CODE"),
            stateParams(state = "FUTURE_STATE"),
            stateParams(authSessionId = " "),
            stateParams(generation = -1),
        )

        invalid.forEach { params ->
            assertFailsWith<SerializationException> {
                BusinessAuthStateChangedCodec.decode(
                    AgentRawNotification("business/auth/state-changed", params),
                )
            }
        }
    }

    @Test
    fun `rejects a different notification method`() {
        assertFailsWith<SerializationException> {
            BusinessAuthStateChangedCodec.decode(
                AgentRawNotification("future/auth-event", stateParams()),
            )
        }
    }

    private fun stateParams(
        authSessionId: String = "auth-session-1",
        state: String = "SIGNED_OUT",
        generation: Long = 3,
        businessCode: String = "BUSINESS_MEMBERSHIP_EXPIRED",
    ) = buildJsonObject {
        put("authSessionId", authSessionId)
        put("state", state)
        put("generation", generation)
        put("businessCode", businessCode)
    }
}
