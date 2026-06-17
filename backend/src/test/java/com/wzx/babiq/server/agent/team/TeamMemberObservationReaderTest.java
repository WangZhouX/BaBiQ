package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.conversation.repository.ToolCallRecord;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 团队成员观测归属测试。
 *
 * <p>团队成员面板里的工具次数必须来自已有 `bq_tool_calls` 归属链路，不能再由
 * `TeamCoordinationTool` 写死为 0。</p>
 */
class TeamMemberObservationReaderTest {

    @Test
    void read_should_count_tool_calls_by_turn_and_member_name() {
        ToolCallPersistenceService persistenceService = mock(ToolCallPersistenceService.class);
        when(persistenceService.listByTurnId("turn_team")).thenReturn(List.of(
                toolCall("call_1", "turn_team", "writer"),
                toolCall("call_2", "turn_team", "reviewer"),
                toolCall("call_3", "turn_team", "writer")));
        TeamMemberObservationReader reader = new ToolCallTeamMemberObservationReader(
                persistenceService,
                new ApproximateContextTokenEstimator());

        TeamMemberObservation observation = reader.read("turn_team", "writer", "writer 输出正文");

        assertThat(observation.toolCallCount()).isEqualTo(2);
        assertThat(observation.tokenEstimate()).isPositive();
    }

    private ToolCallRecord toolCall(String id, String turnId, String agentName) {
        Instant now = Instant.parse("2026-06-16T10:00:00Z");
        return new ToolCallRecord(
                id,
                "thread_team",
                turnId,
                "read_file",
                "{}",
                "completed",
                "ok",
                null,
                agentName,
                "babiq_agent",
                "dlg_" + agentName,
                now,
                now.plusSeconds(1));
    }
}
