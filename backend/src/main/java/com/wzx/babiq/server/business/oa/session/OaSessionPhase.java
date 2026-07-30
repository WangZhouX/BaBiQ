package com.wzx.babiq.server.business.oa.session;

/** OA 会话持久化阶段；REVOKED 是终态，禁止任何凭据恢复。 */
public enum OaSessionPhase {
    SIGNED_OUT,
    AUTHENTICATING,
    RESTORING,
    INSTALLING,
    READY,
    DETACHED,
    REVOKING,
    REVOKED
}
