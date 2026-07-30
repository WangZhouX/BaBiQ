package com.wzx.babiq.server.business.api.dto;

import java.util.List;
import java.util.Set;

/** Stable, non-secret projections exposed by the business authentication RPC. */
public final class BusinessAuthDtos {
    private BusinessAuthDtos() { }

    public record Session(
            String authSessionId,
            String state,
            long identityEpoch,
            long generation,
            String attachHandle,
            String userId,
            String userName,
            String tenantId,
            String tenantName,
            String platformId,
            Set<String> roles,
            Set<String> permissions,
            String rememberedAccount,
            boolean canRestore,
            boolean canAttach) {
        public Session {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }

        /** Compatibility constructor for callers that do not issue reconnect handles. */
        public Session(String authSessionId, String state, long identityEpoch,
                       String userId, String userName, String tenantId, String tenantName,
                       String platformId, Set<String> roles, Set<String> permissions,
                       String rememberedAccount, boolean canRestore, boolean canAttach) {
            this(authSessionId, state, identityEpoch, 0L, null, userId, userName, tenantId,
                    tenantName, platformId, roles, permissions, rememberedAccount, canRestore, canAttach);
        }

        @Override
        public String toString() {
            return "Session[authSessionId=" + authSessionId + ", state=" + state
                    + ", identityEpoch=" + identityEpoch + ", generation=" + generation
                    + ", attachHandle=" + (attachHandle == null ? "null" : "[REDACTED]")
                    + ", userId=" + userId + ", tenantId=" + tenantId
                    + ", platformId=" + platformId + ", canRestore=" + canRestore
                    + ", canAttach=" + canAttach + "]";
        }
    }

    public record TenantCandidate(String candidateId, String tenantName, int platformId,
                                  int tenantEnterStatus) { }

    public record TenantCandidates(List<TenantCandidate> candidates, String account) {
        public TenantCandidates { candidates = List.copyOf(candidates); }
    }
}
