package com.wzx.babiq.server.observability;

import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * 负责在 turn 收尾时发出协议摘要并记录本地指标。
 */
@Component
public class TurnSummaryEmitter {

    private final ConversationService conversationService;
    private final CostCalculator costCalculator;
    private final BaBiQMetrics metrics;
    private final StructuredTurnLogger structuredTurnLogger;

    public TurnSummaryEmitter(ConversationService conversationService,
                              CostCalculator costCalculator,
                              BaBiQMetrics metrics,
                              StructuredTurnLogger structuredTurnLogger) {
        this.conversationService = conversationService;
        this.costCalculator = costCalculator;
        this.metrics = metrics;
        this.structuredTurnLogger = structuredTurnLogger;
    }

    public TurnSummaryItem emit(TurnObservationContext context, ItemEmitter emitter, String status) throws IOException {
        BigDecimal cost = costCalculator.estimate(context.model(), context.promptTokens(), context.completionTokens());
        TurnSummaryItem item = conversationService.emitTurnSummary(
                status,
                context.model(),
                context.promptTokens(),
                context.completionTokens(),
                context.totalTokens(),
                context.toolCalls(),
                cost,
                context.durationMs());
        emitter.emitTurnSummary(item);
        recordMetrics(context, status);
        structuredTurnLogger.logSummary(context, item);
        return item;
    }

    private void recordMetrics(TurnObservationContext context, String status) {
        metrics.recordTurn(status);
        metrics.recordTokens(context.promptTokens(), context.completionTokens());
    }
}
