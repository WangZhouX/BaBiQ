package com.wzx.babiq.server.application.auth;

import java.util.Objects;
import java.util.Set;

/**
 * 由已认证桌面连接和 identity 消息共同建立的可信业务身份快照。
 *
 * <p>该类型只在服务端内存中流转；集合会防御复制，日志文本会隐藏全部业务标识。</p>
 */
public record TrustedBusinessIdentity(
        String reservationId,
        String webSocketSessionId,
        String desktopInstanceId,
        String desktopSessionId,
        String authSessionId,
        long identityEpoch,
        String userId,
        String tenantId,
        String platformId,
        Set<String> roles,
        Set<String> permissions
) {
    public TrustedBusinessIdentity {
        requireText(reservationId, "reservationId");
        requireText(webSocketSessionId, "webSocketSessionId");
        requireText(desktopInstanceId, "desktopInstanceId");
        requireText(desktopSessionId, "desktopSessionId");
        requireText(authSessionId, "authSessionId");
        requireText(userId, "userId");
        requireText(tenantId, "tenantId");
        requireText(platformId, "platformId");
        if (identityEpoch <= 0) {
            throw new IllegalArgumentException("identityEpoch must be positive");
        }
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
    }

    @Override
    public String toString() {
        return "TrustedBusinessIdentity(reservationId=[REDACTED], webSocketSessionId=[REDACTED], desktopInstanceId=[REDACTED], "
                + "desktopSessionId=[REDACTED], authSessionId=[REDACTED], identityEpoch=" + identityEpoch
                + ", userId=[REDACTED], tenantId=[REDACTED], platformId=[REDACTED], roles=" + roles.size()
                + ", permissions=" + permissions.size() + ")";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
