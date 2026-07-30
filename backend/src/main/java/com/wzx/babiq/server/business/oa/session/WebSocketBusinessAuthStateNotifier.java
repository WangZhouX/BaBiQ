package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.action.ApplicationOutboundJsonRpcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Sends terminal OA authentication state changes over the already trusted local WebSocket. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class WebSocketBusinessAuthStateNotifier implements BusinessAuthStateNotifier {

    private static final String METHOD = "business/auth/state-changed";

    private final ApplicationOutboundJsonRpcClient outbound;

    public WebSocketBusinessAuthStateNotifier(ApplicationOutboundJsonRpcClient outbound) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
    }

    @Override
    public void signedOut(
            ReadyOaSessionLease lease,
            OaSessionRecord signedOut,
            OaRemoteRequestException.TerminalReason reason) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(signedOut, "signedOut");
        Objects.requireNonNull(reason, "reason");
        outbound.sendNotification(
                lease.webSocketSessionId(),
                METHOD,
                new StateChanged(
                        signedOut.authSessionId(),
                        signedOut.phase().name(),
                        signedOut.generation(),
                        businessCode(reason)));
    }

    private static String businessCode(OaRemoteRequestException.TerminalReason reason) {
        return switch (reason) {
            case AUTH_EXPIRED -> "BUSINESS_AUTH_EXPIRED";
            case MEMBERSHIP_EXPIRED -> "BUSINESS_MEMBERSHIP_EXPIRED";
        };
    }

    private record StateChanged(
            String authSessionId,
            String state,
            long generation,
            String businessCode) {
    }
}
