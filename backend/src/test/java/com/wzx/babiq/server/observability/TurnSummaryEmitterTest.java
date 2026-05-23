package com.wzx.babiq.server.observability;

import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class TurnSummaryEmitterTest {

    @Test
    void emit_should_send_turn_summary_and_record_metrics() throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties(Map.of(
                "qwen-plus", new ObservabilityProperties.ModelPricing(
                        new BigDecimal("0.20"), new BigDecimal("0.60"))));
        BaBiQMetrics metrics = new BaBiQMetrics();
        TurnSummaryEmitter emitter = new TurnSummaryEmitter(
                new ConversationService(),
                new CostCalculator(properties),
                metrics,
                new StructuredTurnLogger());
        TurnObservationContext context = TurnObservationContext.start(
                "thr_1", "turn_1", "provider-a", "qwen-plus", () -> 1_000_000L);
        context.recordTokens(1000L, 2000L);
        context.recordToolCall("read_file");
        List<ThreadItem> emitted = new ArrayList<>();

        emitter.emit(context, capturingEmitter(emitted), "completed");

        TurnSummaryItem item = (TurnSummaryItem) emitted.get(0);
        assertThat(item.type()).isEqualTo("turnSummary");
        assertThat(item.status()).isEqualTo("completed");
        assertThat(item.estimatedCostUsd()).isEqualByComparingTo("0.00140000");
        assertThat(metrics.snapshot().turnsByStatus()).containsEntry("completed", 1L);
    }

    @Test
    void structured_logger_should_render_single_line_json() throws Exception {
        TurnObservationContext context = TurnObservationContext.start(
                "thr_1", "turn_1", "provider-a", "qwen-plus", () -> 1_000_000L);
        TurnSummaryItem item = new TurnSummaryItem(
                "it_1", "turnSummary", "completed", "qwen-plus",
                1L, 2L, 3L, 1, new BigDecimal("0.0001"), 10L);

        String json = new StructuredTurnLogger().toJson(context, item);

        assertThat(json)
                .contains("\"event\":\"agent.turn.summary\"")
                .contains("\"threadId\":\"thr_1\"")
                .doesNotContain("\n");
    }

    private ItemEmitter capturingEmitter(List<ThreadItem> emitted) throws Exception {
        ItemEmitter emitter = mock(ItemEmitter.class);
        doAnswer(invocation -> {
            emitted.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitTurnSummary(any(TurnSummaryItem.class));
        return emitter;
    }
}
