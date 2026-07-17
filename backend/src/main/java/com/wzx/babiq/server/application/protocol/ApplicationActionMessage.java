package com.wzx.babiq.server.application.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** 桌面动作请求、进度、终态或查询消息。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationActionMessage(
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
        String threadId,
        String turnId,
        String toolCallId,
        String executionId,
        JsonNode payload
) implements ApplicationEnvelope {
    public ApplicationActionMessage {
        payload = payload == null ? null : payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload == null ? null : payload.deepCopy();
    }
}
