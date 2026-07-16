package com.wzx.huitai.agent.protocol

import com.wzx.huitai.action.model.ActionErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApplicationProtocolValidationTest {
    @Test
    fun `protocol limits use the specified byte budgets`() {
        assertEquals(256 * 1024, ApplicationProtocolLimits.MAX_ENVELOPE_BYTES)
        assertEquals(128 * 1024, ApplicationProtocolLimits.MAX_CATALOG_PAYLOAD_BYTES)
        assertEquals(128 * 1024, ApplicationProtocolLimits.MAX_CONTEXT_PAYLOAD_BYTES)
        assertEquals(64 * 1024, ApplicationProtocolLimits.MAX_ACTION_INPUT_BYTES)
        assertEquals(64 * 1024, ApplicationProtocolLimits.MAX_ACTION_RESULT_BYTES)
    }

    @Test
    fun `exact byte boundaries are accepted and one byte over is rejected`() {
        assertBoundary(
            ApplicationProtocolLimits.MAX_ENVELOPE_BYTES,
            ApplicationProtocolValidator::validateEnvelopeSize,
        )
        assertBoundary(
            ApplicationProtocolLimits.MAX_CATALOG_PAYLOAD_BYTES,
            ApplicationProtocolValidator::validateCatalogPayloadSize,
        )
        assertBoundary(
            ApplicationProtocolLimits.MAX_CONTEXT_PAYLOAD_BYTES,
            ApplicationProtocolValidator::validateContextPayloadSize,
        )
        assertBoundary(
            ApplicationProtocolLimits.MAX_ACTION_INPUT_BYTES,
            ApplicationProtocolValidator::validateActionInputSize,
        )
        assertBoundary(
            ApplicationProtocolLimits.MAX_ACTION_RESULT_BYTES,
            ApplicationProtocolValidator::validateActionResultSize,
        )
    }

    @Test
    fun `rejects unsupported protocol versions`() {
        ApplicationProtocolValidator.validate(commonFields(protocolVersion = "1.0"))

        assertFailsWith<ApplicationProtocolValidationException> {
            ApplicationProtocolValidator.validate(commonFields(protocolVersion = "2.0"))
        }
    }

    @Test
    fun `rejects nonpositive common ordering values`() {
        listOf(0L, -1L).forEach { invalid ->
            assertFailsWith<ApplicationProtocolValidationException> {
                ApplicationProtocolValidator.validate(commonFields(sequence = invalid))
            }
            assertFailsWith<ApplicationProtocolValidationException> {
                ApplicationProtocolValidator.validate(commonFields(identityEpoch = invalid))
            }
        }
    }

    @Test
    fun `rejects nonpositive catalog and context epochs`() {
        listOf(0L, -1L).forEach { invalid ->
            assertFailsWith<ApplicationProtocolValidationException> {
                ApplicationProtocolValidator.validate(
                    CatalogEnvelope(
                        common = commonFields(),
                        catalogEpoch = invalid,
                        contextSequence = 1,
                        payloadSize = 0,
                        payload = kotlinx.serialization.json.JsonObject(emptyMap()),
                    ),
                )
            }
            assertFailsWith<ApplicationProtocolValidationException> {
                ApplicationProtocolValidator.validate(
                    ContextEnvelope(
                        common = commonFields(),
                        catalogEpoch = 1,
                        contextSequence = invalid,
                        payloadSize = 0,
                        payload = kotlinx.serialization.json.JsonObject(emptyMap()),
                    ),
                )
            }
        }
    }

    @Test
    fun `business identity may be null only when signed out`() {
        ApplicationProtocolValidator.validate(
            IdentityEnvelope(
                common = commonFields(
                    authSessionId = null,
                    userId = null,
                    tenantId = null,
                    platformId = null,
                ),
                authenticated = false,
                roles = emptySet(),
                permissions = emptySet(),
            ),
        )

        listOf(
            commonFields(authSessionId = null),
            commonFields(userId = null),
            commonFields(tenantId = null),
            commonFields(platformId = null),
        ).forEach { common ->
            assertFailsWith<ApplicationProtocolValidationException> {
                ApplicationProtocolValidator.validate(
                    IdentityEnvelope(
                        common = common,
                        authenticated = true,
                        roles = emptySet(),
                        permissions = emptySet(),
                    ),
                )
            }
        }
    }

    @Test
    fun `all business envelopes require complete identity except signed out update`() {
        val incompleteIdentities = listOf(
            commonFields(authSessionId = null),
            commonFields(userId = null),
            commonFields(tenantId = null),
            commonFields(platformId = null),
        )

        incompleteIdentities.forEach { common ->
            val businessEnvelopes = listOf<ApplicationEnvelope>(
                CatalogEnvelope(
                    common = common,
                    catalogEpoch = 1,
                    contextSequence = 1,
                    payloadSize = 0,
                    payload = kotlinx.serialization.json.JsonObject(emptyMap()),
                ),
                ContextEnvelope(
                    common = common,
                    catalogEpoch = 1,
                    contextSequence = 1,
                    payloadSize = 0,
                    payload = kotlinx.serialization.json.JsonObject(emptyMap()),
                ),
                ActionEnvelope(
                    common = common,
                    threadId = "thread-1",
                    turnId = "turn-1",
                    toolCallId = "tool-call-1",
                    executionId = "execution-1",
                    payload = kotlinx.serialization.json.JsonObject(emptyMap()),
                ),
                IdentityEnvelope(
                    common = common,
                    authenticated = true,
                    roles = emptySet(),
                    permissions = emptySet(),
                ),
            )

            businessEnvelopes.forEach { envelope ->
                assertFailsWith<ApplicationProtocolValidationException> {
                    ApplicationProtocolValidator.validate(envelope)
                }
            }
        }
    }

    @Test
    fun `terminal wire names match protocol vocabulary`() {
        assertEquals(
            setOf("completed", "failed", "rejected", "canceled", "expired", "outcome_unknown"),
            ApplicationActionTerminal.entries.map { it.wireName }.toSet(),
        )
        assertEquals(6, ApplicationActionTerminal.entries.size)
    }

    @Test
    fun `all sixteen protocol error names match action core vocabulary`() {
        val expected = setOf(
            "action_not_found",
            "action_disabled",
            "permission_denied",
            "validation_failed",
            "context_stale",
            "approval_denied",
            "approval_expired",
            "execution_conflict",
            "execution_timeout",
            "desktop_disconnected",
            "agent_disconnected",
            "auth_expired",
            "membership_expired",
            "remote_request_failed",
            "outcome_unknown",
            "protocol_error",
        )
        val actionCoreNames = ActionErrorCode.entries.map {
            ApplicationProtocol.JSON.encodeToString(ActionErrorCode.serializer(), it).trim('"')
        }.toSet()

        assertEquals(16, ActionErrorCode.entries.size)
        assertEquals(expected, actionCoreNames)
        assertEquals(actionCoreNames, ApplicationProtocolErrorCode.entries.map { it.wireName }.toSet())
    }

    private fun assertBoundary(limit: Int, validator: (ByteArray) -> Unit) {
        validator(ByteArray(limit))
        assertFailsWith<ApplicationProtocolValidationException> {
            validator(ByteArray(limit + 1))
        }
    }

    private fun commonFields(
        protocolVersion: String = ApplicationProtocol.PROTOCOL_VERSION,
        authSessionId: String? = "auth-session-1",
        identityEpoch: Long = 8,
        sequence: Long = 1,
        userId: String? = "user-1",
        tenantId: String? = "tenant-1",
        platformId: String? = "platform-1",
    ) = CommonApplicationFields(
        protocolVersion = protocolVersion,
        desktopInstanceId = "desktop-1",
        desktopSessionId = "desktop-session-1",
        authSessionId = authSessionId,
        identityEpoch = identityEpoch,
        sequence = sequence,
        generatedAt = "2026-07-16T10:00:00Z",
        userId = userId,
        tenantId = tenantId,
        platformId = platformId,
    )
}
