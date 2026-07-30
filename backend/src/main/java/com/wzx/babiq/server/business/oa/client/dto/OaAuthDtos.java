package com.wzx.babiq.server.business.oa.client.dto;

import java.util.List;

public final class OaAuthDtos {
    private OaAuthDtos() {}
    public record OaTenantCandidate(String userId, String tenantId, int platformId, String tenantName,
                                    int tenantEnterStatus, String tenantEnterId, String account) {
        /** Keeps the account that produced the candidate so login never substitutes a remote user id. */
        public OaTenantCandidate(String userId, String tenantId, int platformId, String tenantName,
                                 int tenantEnterStatus, String tenantEnterId) {
            this(userId, tenantId, platformId, tenantName, tenantEnterStatus, tenantEnterId, null);
        }
    }
    public record OaCredential(String accessToken, String refreshToken, String userId, long expiresTime) {
        @Override public String toString() { return "OaCredential(accessToken=[REDACTED], refreshToken=[REDACTED], userId=" + userId + ", expiresTime=" + expiresTime + ")"; }
    }
    public record OaPermissionSnapshot(List<String> permissions, List<String> roles, String userId, String userName, List<Object> menus) {
        public OaPermissionSnapshot { permissions = List.copyOf(permissions); roles = List.copyOf(roles); menus = List.copyOf(menus); }
    }
}
