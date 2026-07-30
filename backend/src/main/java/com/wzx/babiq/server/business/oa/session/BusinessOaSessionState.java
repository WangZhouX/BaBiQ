package com.wzx.babiq.server.business.oa.session;

/** Server-owned OA session states. A client cannot publish or skip these transitions. */
public enum BusinessOaSessionState {
    SIGNED_OUT,
    AUTHENTICATING,
    RESTORING,
    INSTALLING,
    READY,
    DETACHED,
    REVOKING,
    REVOKED;

    static BusinessOaSessionState from(OaSessionPhase phase) {
        return valueOf(phase.name());
    }

    OaSessionPhase toPhase() {
        return OaSessionPhase.valueOf(name());
    }
}
