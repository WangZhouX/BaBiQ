package com.wzx.babiq.server.application.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Set;
import java.util.Objects;

/** 业务身份绑定或更新消息。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationIdentityMessage(
        String protocolVersion,
        String desktopInstanceId,
        String desktopSessionId,
        String authSessionId,
        long identityEpoch,
        long sequence,
        String generatedAt,
        String userId,
        String tenantId,
        String platformId,
        boolean authenticated,
        Set<String> roles,
        Set<String> permissions
) implements ApplicationEnvelope {
    public ApplicationIdentityMessage {
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
    }
}
