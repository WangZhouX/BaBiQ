package com.wzx.babiq.server.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaBiQMetricsTest {

    @Test
    void metrics_should_snapshot_turns_tokens_tools_and_approval_decisions() {
        BaBiQMetrics metrics = new BaBiQMetrics();

        metrics.recordTurn("completed");
        metrics.recordTokens(100L, 50L);
        metrics.recordToolCall("read_file");
        metrics.recordToolCall("read_file");
        metrics.recordApprovalDecision("approved");

        BaBiQMetricsSnapshot snapshot = metrics.snapshot();

        assertThat(snapshot.turnsByStatus()).containsEntry("completed", 1L);
        assertThat(snapshot.promptTokens()).isEqualTo(100L);
        assertThat(snapshot.completionTokens()).isEqualTo(50L);
        assertThat(snapshot.toolCallsByName()).containsEntry("read_file", 2L);
        assertThat(snapshot.approvalDecisionsByDecision()).containsEntry("approved", 1L);
    }
}
