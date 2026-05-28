package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.dto.MemoryReferenceInfo;
import com.wzx.babiq.server.api.dto.MemorySearchResult;
import com.wzx.babiq.server.context.ContextTokenEstimator;
import com.wzx.babiq.server.memory.retrieval.LongTermMemoryRetrievalResult;
import com.wzx.babiq.server.memory.retrieval.LongTermMemoryRetrievalService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * memory/search JSON-RPC handler。
 *
 * <p>该接口用于设置页或调试面板人工验证长期记忆检索效果；Agent 正式 read path 会在
 * ContextWindowRuntime 中自动调用同一服务并写入快照审计。</p>
 */
@Component
public class MemorySearchHandler implements JsonRpcMethodHandler {

    /** 长期记忆检索服务。 */
    private final LongTermMemoryRetrievalService retrievalService;
    /** token 预估器，用于返回每条引用的轻量预算信息。 */
    private final ContextTokenEstimator tokenEstimator;

    public MemorySearchHandler(LongTermMemoryRetrievalService retrievalService,
                               ContextTokenEstimator tokenEstimator) {
        this.retrievalService = retrievalService;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public String method() {
        return "memory/search";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String query = ContextStatusHandler.requiredText(params, "query");
        int modelContextWindow = params == null || !params.hasNonNull("modelContextWindow")
                ? 32_768
                : params.get("modelContextWindow").asInt(32_768);
        LongTermMemoryRetrievalResult result = retrievalService.retrievePreview(query, modelContextWindow);
        return new MemorySearchResult(
                LongTermMemoryRetrievalService.STRATEGY,
                result.references().stream()
                        .map(reference -> new MemoryReferenceInfo(
                                reference.artifactId(),
                                reference.confidence(),
                                reference.text(),
                                tokenEstimator.estimate(reference.text())))
                        .toList(),
                result.tokenEstimate());
    }

}
