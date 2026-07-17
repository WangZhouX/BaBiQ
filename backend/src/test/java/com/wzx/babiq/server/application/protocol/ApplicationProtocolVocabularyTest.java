package com.wzx.babiq.server.application.protocol;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationProtocolVocabularyTest {

    @Test
    void terminalWireNamesMatchTheCanonicalSixValues() {
        assertEquals(
                Set.of("completed", "failed", "rejected", "canceled", "expired", "outcome_unknown"),
                ApplicationProtocolValidator.ApplicationActionTerminal.wireNames());
        assertEquals(6, ApplicationProtocolValidator.ApplicationActionTerminal.values().length);
    }

    @Test
    void protocolErrorWireNamesMatchAllSixteenActionCoreValues() {
        Set<String> expected = Set.of(
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
                "protocol_error");

        assertEquals(expected, ApplicationProtocolValidator.ApplicationProtocolErrorCode.wireNames());
        assertEquals(16, ApplicationProtocolValidator.ApplicationProtocolErrorCode.values().length);
        assertEquals(expected, Set.of(ApplicationProtocolValidator.ApplicationProtocolErrorCode.values()).stream()
                .map(ApplicationProtocolValidator.ApplicationProtocolErrorCode::wireName)
                .collect(Collectors.toSet()));
    }
}
