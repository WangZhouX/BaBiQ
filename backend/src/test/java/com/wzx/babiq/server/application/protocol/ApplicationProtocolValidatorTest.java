package com.wzx.babiq.server.application.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApplicationProtocolValidatorTest {

    @Test
    void nullEnvelopeUsesTheStableProtocolValidationException() {
        assertThrows(ApplicationProtocolValidator.ApplicationProtocolValidationException.class,
                () -> ApplicationProtocolValidator.validate(null));
    }

    @Test
    void exactByteLimitsAreAcceptedAndOneByteOverIsRejected() {
        assertBoundary(ApplicationProtocolValidator.MAX_ENVELOPE_BYTES,
                ApplicationProtocolValidator::validateEnvelopeSize);
        assertBoundary(ApplicationProtocolValidator.MAX_CATALOG_PAYLOAD_BYTES,
                ApplicationProtocolValidator::validateCatalogPayloadSize);
        assertBoundary(ApplicationProtocolValidator.MAX_CONTEXT_PAYLOAD_BYTES,
                ApplicationProtocolValidator::validateContextPayloadSize);
        assertBoundary(ApplicationProtocolValidator.MAX_ACTION_INPUT_BYTES,
                ApplicationProtocolValidator::validateActionInputSize);
        assertBoundary(ApplicationProtocolValidator.MAX_ACTION_RESULT_BYTES,
                ApplicationProtocolValidator::validateActionResultSize);
    }

    @Test
    void commonFieldsRequireSupportedVersionAndPositiveOrderingValues() {
        assertThrows(ApplicationProtocolValidator.ApplicationProtocolValidationException.class,
                () -> ApplicationProtocolValidator.validate(identity("2.0", 8, 1, true,
                        "auth-session-1", "user-1", "tenant-1", "platform-1")));
        for (long invalid : new long[]{0, -1}) {
            assertThrows(ApplicationProtocolValidator.ApplicationProtocolValidationException.class,
                    () -> ApplicationProtocolValidator.validate(identity("1.0", invalid, 1, true,
                            "auth-session-1", "user-1", "tenant-1", "platform-1")));
            assertThrows(ApplicationProtocolValidator.ApplicationProtocolValidationException.class,
                    () -> ApplicationProtocolValidator.validate(identity("1.0", 8, invalid, true,
                            "auth-session-1", "user-1", "tenant-1", "platform-1")));
        }
    }

    @Test
    void signedOutIdentityAllowsNullableBusinessIdentityFields() {
        ApplicationIdentityMessage signedOut = identity("1.0", 9, 2, false,
                null, null, null, null);

        assertDoesNotThrow(() -> ApplicationProtocolValidator.validate(signedOut));
    }

    @Test
    void authenticatedAndBusinessEnvelopesRequireCompleteIdentity() {
        for (String missing : Set.of("authSessionId", "userId", "tenantId", "platformId")) {
            ApplicationIdentityMessage incompleteIdentity = identity(
                    "1.0",
                    8,
                    1,
                    true,
                    missing.equals("authSessionId") ? null : "auth-session-1",
                    missing.equals("userId") ? null : "user-1",
                    missing.equals("tenantId") ? null : "tenant-1",
                    missing.equals("platformId") ? null : "platform-1");
            assertThrows(ApplicationProtocolValidator.ApplicationProtocolValidationException.class,
                    () -> ApplicationProtocolValidator.validate(incompleteIdentity), missing);
        }

        ApplicationActionMessage action = new ApplicationActionMessage(
                "1.0", "desktop-1", "desktop-session-1", null, 8, 1,
                "2026-07-16T10:00:00Z", "user-1", "tenant-1", "platform-1",
                "thread-1", "turn-1", "tool-call-1", "execution-1", emptyPayload());
        assertThrows(ApplicationProtocolValidator.ApplicationProtocolValidationException.class,
                () -> ApplicationProtocolValidator.validate(action));
    }

    @Test
    void catalogAndContextOrderingMustBePositive() {
        for (long invalid : new long[]{0, -1}) {
            ApplicationCatalogMessage catalog = new ApplicationCatalogMessage(
                    "1.0", "desktop-1", "desktop-session-1", "auth-session-1", 8, 1,
                    "2026-07-16T10:00:00Z", "user-1", "tenant-1", "platform-1",
                    invalid, 1, 0, emptyPayload());
            ApplicationCatalogMessage context = new ApplicationCatalogMessage(
                    "1.0", "desktop-1", "desktop-session-1", "auth-session-1", 8, 1,
                    "2026-07-16T10:00:00Z", "user-1", "tenant-1", "platform-1",
                    1, invalid, 0, emptyPayload());

            assertThrows(ApplicationProtocolValidator.ApplicationProtocolValidationException.class,
                    () -> ApplicationProtocolValidator.validate(catalog));
            assertThrows(ApplicationProtocolValidator.ApplicationProtocolValidationException.class,
                    () -> ApplicationProtocolValidator.validate(context));
        }
    }

    private static void assertBoundary(int maximum, ByteValidator validator) {
        assertDoesNotThrow(() -> validator.validate(new byte[maximum]));
        assertThrows(ApplicationProtocolValidator.ApplicationProtocolValidationException.class,
                () -> validator.validate(new byte[maximum + 1]));
    }

    private static ApplicationIdentityMessage identity(
            String version,
            long identityEpoch,
            long sequence,
            boolean authenticated,
            String authSessionId,
            String userId,
            String tenantId,
            String platformId
    ) {
        return new ApplicationIdentityMessage(
                version, "desktop-1", "desktop-session-1", authSessionId, identityEpoch, sequence,
                "2026-07-16T10:00:00Z", userId, tenantId, platformId,
                authenticated, Set.of(), Set.of());
    }

    private static ObjectNode emptyPayload() {
        return ApplicationProtocol.objectNode();
    }

    @FunctionalInterface
    private interface ByteValidator {
        void validate(byte[] bytes);
    }
}
