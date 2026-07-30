package com.wzx.babiq.server.business.oa.session;

/** Publishes non-sensitive authentication lifecycle changes to the owning desktop connection. */
@FunctionalInterface
public interface BusinessAuthStateNotifier {

    void signedOut(
            ReadyOaSessionLease lease,
            OaSessionRecord signedOut,
            OaRemoteRequestException.TerminalReason reason);

    static BusinessAuthStateNotifier noop() {
        return (lease, signedOut, reason) -> { };
    }
}
