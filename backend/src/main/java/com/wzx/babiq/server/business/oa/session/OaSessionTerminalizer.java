package com.wzx.babiq.server.business.oa.session;

/** Completes one exact READY lease's local terminal-authentication revocation. */
@FunctionalInterface
public interface OaSessionTerminalizer {
    void terminate(ReadyOaSessionLease lease, OaRemoteRequestException.TerminalReason reason);
}
