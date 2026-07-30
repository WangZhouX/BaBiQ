package com.wzx.babiq.server.business.oa.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.action.ApplicationOutboundJsonRpcClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebSocketBusinessAuthStateNotifierTest {

    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
            "AUTH_EXPIRED, BUSINESS_AUTH_EXPIRED",
            "MEMBERSHIP_EXPIRED, BUSINESS_MEMBERSHIP_EXPIRED"
    })
    void publishesOnlyStableNonSensitiveTerminalState(
            OaRemoteRequestException.TerminalReason reason,
            String businessCode) {
        ApplicationOutboundJsonRpcClient outbound = mock(ApplicationOutboundJsonRpcClient.class);
        WebSocketBusinessAuthStateNotifier notifier = new WebSocketBusinessAuthStateNotifier(outbound);
        ReadyOaSessionLease lease = lease();
        OaSessionRecord signedOut = OaSessionRecord.signedOut(
                "auth-1", "desktop-1", "session-1", Instant.now());
        signedOut = new OaSessionRecord(
                signedOut.authSessionId(), signedOut.desktopInstanceId(), signedOut.desktopSessionId(),
                null, null, null, OaSessionPhase.SIGNED_OUT, 3,
                null, null, 1, null, null, null, Instant.now(), Instant.now());

        notifier.signedOut(lease, signedOut, reason);

        ArgumentCaptor<Object> params = ArgumentCaptor.forClass(Object.class);
        verify(outbound).sendNotification(
                eq("ws-1"), eq("business/auth/state-changed"), params.capture());
        var wire = json.valueToTree(params.getValue());
        assertThat(wire.path("authSessionId").asText()).isEqualTo("auth-1");
        assertThat(wire.path("state").asText()).isEqualTo("SIGNED_OUT");
        assertThat(wire.path("generation").asLong()).isEqualTo(3);
        assertThat(wire.path("businessCode").asText()).isEqualTo(businessCode);
        assertThat(wire.toString()).doesNotContain("access", "refresh", "credential");
    }

    private static ReadyOaSessionLease lease() {
        return new ReadyOaSessionLease(
                "auth-1", "desktop-1", "session-1", "ws-1",
                "user-1", "tenant-1", "2", 1, "credential-ref", 1, Instant.now());
    }
}
