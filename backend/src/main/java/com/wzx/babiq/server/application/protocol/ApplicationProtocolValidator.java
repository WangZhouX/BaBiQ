package com.wzx.babiq.server.application.protocol;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** 业务桌面应用协议的尺寸、版本、顺序和身份完整性校验。 */
public final class ApplicationProtocolValidator {

    public static final int MAX_ENVELOPE_BYTES = 256 * 1024;
    public static final int MAX_CATALOG_PAYLOAD_BYTES = 128 * 1024;
    public static final int MAX_CONTEXT_PAYLOAD_BYTES = 128 * 1024;
    public static final int MAX_ACTION_INPUT_BYTES = 64 * 1024;
    public static final int MAX_ACTION_RESULT_BYTES = 64 * 1024;

    private ApplicationProtocolValidator() {
    }

    public static void validateEnvelopeSize(byte[] bytes) {
        validateSize("envelope", bytes, MAX_ENVELOPE_BYTES);
    }

    public static void validateCatalogPayloadSize(byte[] bytes) {
        validateSize("catalog payload", bytes, MAX_CATALOG_PAYLOAD_BYTES);
    }

    public static void validateContextPayloadSize(byte[] bytes) {
        validateSize("context payload", bytes, MAX_CONTEXT_PAYLOAD_BYTES);
    }

    public static void validateActionInputSize(byte[] bytes) {
        validateSize("action input", bytes, MAX_ACTION_INPUT_BYTES);
    }

    public static void validateActionResultSize(byte[] bytes) {
        validateSize("action result", bytes, MAX_ACTION_RESULT_BYTES);
    }

    public static void validate(ApplicationEnvelope envelope) {
        requireValid(envelope != null, "Application envelope must not be null");
        requireValid(ApplicationProtocol.PROTOCOL_VERSION.equals(envelope.protocolVersion()),
                "Unsupported application protocol version");
        requirePositive("identityEpoch", envelope.identityEpoch());
        requirePositive("sequence", envelope.sequence());

        if (envelope instanceof ApplicationIdentityMessage identity) {
            if (identity.authenticated()) {
                validateAuthenticatedIdentity(identity);
            }
            return;
        }

        validateAuthenticatedIdentity(envelope);
        if (envelope instanceof ApplicationCatalogMessage catalog) {
            requirePositive("catalogEpoch", catalog.catalogEpoch());
            requirePositive("contextSequence", catalog.contextSequence());
        }
    }

    private static void validateAuthenticatedIdentity(ApplicationEnvelope envelope) {
        requireValid(envelope.authSessionId() != null, "Authenticated identity requires authSessionId");
        requireValid(envelope.userId() != null, "Authenticated identity requires userId");
        requireValid(envelope.tenantId() != null, "Authenticated identity requires tenantId");
        requireValid(envelope.platformId() != null, "Authenticated identity requires platformId");
    }

    private static void validateSize(String category, byte[] bytes, int maximum) {
        requireValid(bytes != null, category + " must not be null");
        requireValid(bytes.length <= maximum,
                category + " exceeds byte limit: actual=" + bytes.length + ", maximum=" + maximum);
    }

    private static void requirePositive(String name, long value) {
        requireValid(value > 0, name + " must be positive");
    }

    private static void requireValid(boolean condition, String message) {
        if (!condition) {
            throw new ApplicationProtocolValidationException(message);
        }
    }

    /** 桌面动作的 6 个稳定终态 wire name。 */
    public enum ApplicationActionTerminal {
        COMPLETED("completed"),
        FAILED("failed"),
        REJECTED("rejected"),
        CANCELED("canceled"),
        EXPIRED("expired"),
        OUTCOME_UNKNOWN("outcome_unknown");

        private final String wireName;

        ApplicationActionTerminal(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Set<String> wireNames() {
            return ApplicationProtocolValidator.wireNames(values());
        }
    }

    /** 应用协议与 Kotlin action core 对齐的 16 个错误码。 */
    public enum ApplicationProtocolErrorCode {
        ACTION_NOT_FOUND("action_not_found"),
        ACTION_DISABLED("action_disabled"),
        PERMISSION_DENIED("permission_denied"),
        VALIDATION_FAILED("validation_failed"),
        CONTEXT_STALE("context_stale"),
        APPROVAL_DENIED("approval_denied"),
        APPROVAL_EXPIRED("approval_expired"),
        EXECUTION_CONFLICT("execution_conflict"),
        EXECUTION_TIMEOUT("execution_timeout"),
        DESKTOP_DISCONNECTED("desktop_disconnected"),
        AGENT_DISCONNECTED("agent_disconnected"),
        AUTH_EXPIRED("auth_expired"),
        MEMBERSHIP_EXPIRED("membership_expired"),
        REMOTE_REQUEST_FAILED("remote_request_failed"),
        OUTCOME_UNKNOWN("outcome_unknown"),
        PROTOCOL_ERROR("protocol_error");

        private final String wireName;

        ApplicationProtocolErrorCode(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Set<String> wireNames() {
            return ApplicationProtocolValidator.wireNames(values());
        }
    }

    private static Set<String> wireNames(ApplicationActionTerminal[] values) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Arrays.stream(values).map(ApplicationActionTerminal::wireName).forEach(names::add);
        return Set.copyOf(names);
    }

    private static Set<String> wireNames(ApplicationProtocolErrorCode[] values) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Arrays.stream(values).map(ApplicationProtocolErrorCode::wireName).forEach(names::add);
        return Set.copyOf(names);
    }

    public static final class ApplicationProtocolValidationException extends IllegalArgumentException {
        public ApplicationProtocolValidationException(String message) {
            super(message);
        }
    }
}
