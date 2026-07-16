package com.wzx.huitai.agent.protocol

import com.wzx.huitai.action.model.ActionErrorCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ApplicationProtocolValidationException(message: String) : IllegalArgumentException(message)

@Serializable
enum class ApplicationActionTerminal(val wireName: String) {
    @SerialName("completed") COMPLETED("completed"),
    @SerialName("failed") FAILED("failed"),
    @SerialName("rejected") REJECTED("rejected"),
    @SerialName("canceled") CANCELED("canceled"),
    @SerialName("expired") EXPIRED("expired"),
    @SerialName("outcome_unknown") OUTCOME_UNKNOWN("outcome_unknown"),
}

/** Protocol projection of action-core error names, guarded by vocabulary parity tests. */
@Serializable
enum class ApplicationProtocolErrorCode(val wireName: String) {
    @SerialName("action_not_found") ACTION_NOT_FOUND("action_not_found"),
    @SerialName("action_disabled") ACTION_DISABLED("action_disabled"),
    @SerialName("permission_denied") PERMISSION_DENIED("permission_denied"),
    @SerialName("validation_failed") VALIDATION_FAILED("validation_failed"),
    @SerialName("context_stale") CONTEXT_STALE("context_stale"),
    @SerialName("approval_denied") APPROVAL_DENIED("approval_denied"),
    @SerialName("approval_expired") APPROVAL_EXPIRED("approval_expired"),
    @SerialName("execution_conflict") EXECUTION_CONFLICT("execution_conflict"),
    @SerialName("execution_timeout") EXECUTION_TIMEOUT("execution_timeout"),
    @SerialName("desktop_disconnected") DESKTOP_DISCONNECTED("desktop_disconnected"),
    @SerialName("agent_disconnected") AGENT_DISCONNECTED("agent_disconnected"),
    @SerialName("auth_expired") AUTH_EXPIRED("auth_expired"),
    @SerialName("membership_expired") MEMBERSHIP_EXPIRED("membership_expired"),
    @SerialName("remote_request_failed") REMOTE_REQUEST_FAILED("remote_request_failed"),
    @SerialName("outcome_unknown") OUTCOME_UNKNOWN("outcome_unknown"),
    @SerialName("protocol_error") PROTOCOL_ERROR("protocol_error"),
    ;

    fun toActionErrorCode(): ActionErrorCode = ActionErrorCode.valueOf(name)

    companion object {
        fun from(value: ActionErrorCode): ApplicationProtocolErrorCode = valueOf(value.name)
    }
}

object ApplicationProtocolValidator {
    fun validateEnvelopeSize(bytes: ByteArray) =
        validateSize("envelope", bytes.size, ApplicationProtocolLimits.MAX_ENVELOPE_BYTES)

    fun validateCatalogPayloadSize(bytes: ByteArray) =
        validateSize("catalog payload", bytes.size, ApplicationProtocolLimits.MAX_CATALOG_PAYLOAD_BYTES)

    fun validateContextPayloadSize(bytes: ByteArray) =
        validateSize("context payload", bytes.size, ApplicationProtocolLimits.MAX_CONTEXT_PAYLOAD_BYTES)

    fun validateActionInputSize(bytes: ByteArray) =
        validateSize("action input", bytes.size, ApplicationProtocolLimits.MAX_ACTION_INPUT_BYTES)

    fun validateActionResultSize(bytes: ByteArray) =
        validateSize("action result", bytes.size, ApplicationProtocolLimits.MAX_ACTION_RESULT_BYTES)

    fun validate(common: CommonApplicationFields) {
        requireValid(common.protocolVersion == ApplicationProtocol.PROTOCOL_VERSION) {
            "Unsupported application protocol version"
        }
        requirePositive("identityEpoch", common.identityEpoch)
        requirePositive("sequence", common.sequence)
    }

    fun validate(envelope: ApplicationEnvelope) {
        validate(envelope.common)
        when (envelope) {
            is CatalogEnvelope -> {
                validateAuthenticatedIdentity(envelope.common)
                requirePositive("catalogEpoch", envelope.catalogEpoch)
                requirePositive("contextSequence", envelope.contextSequence)
            }
            is ContextEnvelope -> {
                validateAuthenticatedIdentity(envelope.common)
                requirePositive("catalogEpoch", envelope.catalogEpoch)
                requirePositive("contextSequence", envelope.contextSequence)
            }
            is IdentityEnvelope -> validateIdentity(envelope)
            is ActionEnvelope -> validateAuthenticatedIdentity(envelope.common)
        }
    }

    private fun validateIdentity(envelope: IdentityEnvelope) {
        if (!envelope.authenticated) return
        validateAuthenticatedIdentity(envelope.common)
    }

    private fun validateAuthenticatedIdentity(common: CommonApplicationFields) {
        requireValid(common.authSessionId != null) { "Authenticated identity requires authSessionId" }
        requireValid(common.userId != null) { "Authenticated identity requires userId" }
        requireValid(common.tenantId != null) { "Authenticated identity requires tenantId" }
        requireValid(common.platformId != null) { "Authenticated identity requires platformId" }
    }

    private fun validateSize(category: String, actualBytes: Int, maximumBytes: Int) {
        requireValid(actualBytes <= maximumBytes) {
            "$category exceeds byte limit: actual=$actualBytes, maximum=$maximumBytes"
        }
    }

    private fun requirePositive(name: String, value: Long) {
        requireValid(value > 0) { "$name must be positive" }
    }

    private inline fun requireValid(condition: Boolean, message: () -> String) {
        if (!condition) throw ApplicationProtocolValidationException(message())
    }
}
