package com.wzx.babiq.server.observability;

import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import org.springframework.stereotype.Component;

import java.io.IOException;
/**
 * 负责在 turn 收尾时发出协议摘要并记录本地指标。
 */
@Component
public class TurnSummaryEmitter {

    /** 负责生成 turnSummary item id，保持所有 conversation item 的 id 风格一致。 */
    private final ConversationService conversationService;
    /** 内存指标聚合器，给后续 actuator/观测面板提供统计数据。 */
    private final BaBiQMetrics metrics;
    /** 输出结构化 turn 日志，方便从后端控制台快速定位某一轮的 token、耗时和状态。 */
    private final StructuredTurnLogger structuredTurnLogger;

    /**
     * 创建 turn 摘要发射器。
     */
    public TurnSummaryEmitter(ConversationService conversationService,
                              BaBiQMetrics metrics,
                              StructuredTurnLogger structuredTurnLogger) {
        this.conversationService = conversationService;
        this.metrics = metrics;
        this.structuredTurnLogger = structuredTurnLogger;
    }

    /**
     * 构造 turnSummary item，推送给桌面端，并记录本地指标。
     */
    public TurnSummaryItem emit(TurnObservationContext context, ItemEmitter emitter, String status) throws IOException {
        TurnSummaryItem item = conversationService.emitTurnSummary(
                status,
                context.model(),
                context.promptTokens(),
                context.completionTokens(),
                context.totalTokens(),
                context.toolCalls(),
                context.durationMs());
        emitter.emitTurnSummary(item);
        recordMetrics(context, status);
        structuredTurnLogger.logSummary(context, item);
        return item;
    }

    /**
     * 记录 P1 内存指标。
     */
    private void recordMetrics(TurnObservationContext context, String status) {
        metrics.recordTurn(status);
        metrics.recordTokens(context.promptTokens(), context.completionTokens());
    }
}
