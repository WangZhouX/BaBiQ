package com.wzx.babiq.server.application.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** 动作目录或页面上下文消息；具体语义由 JSON-RPC method 区分。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationCatalogMessage(
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
        long catalogEpoch,
        long contextSequence,
        int payloadSize,
        JsonNode payload
) implements ApplicationEnvelope {
    public ApplicationCatalogMessage {
        payload = payload == null ? null : payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload == null ? null : payload.deepCopy();
    }
}
