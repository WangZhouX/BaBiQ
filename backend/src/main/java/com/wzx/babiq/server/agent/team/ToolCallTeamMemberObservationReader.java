package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.context.ContextTokenEstimator;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import org.springframework.stereotype.Component;

/**
 * 基于 `bq_tool_calls` 的团队成员观测读取器。
 */
@Component
public class ToolCallTeamMemberObservationReader implements TeamMemberObservationReader {

    /** 工具调用持久化服务，复用 P2-4/P6-1 已有审计链路。 */
    private final ToolCallPersistenceService toolCallPersistenceService;
    /** token 粗估器，保持项目“不计费只估算”的语义。 */
    private final ContextTokenEstimator tokenEstimator;

    /**
     * 创建观测读取器。
     */
    public ToolCallTeamMemberObservationReader(ToolCallPersistenceService toolCallPersistenceService,
                                               ContextTokenEstimator tokenEstimator) {
        this.toolCallPersistenceService = toolCallPersistenceService;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public TeamMemberObservation read(String turnId, String memberName, String fullText) {
        int toolCalls = 0;
        if (turnId != null && !turnId.isBlank()) {
            toolCalls = (int) toolCallPersistenceService.listByTurnId(turnId).stream()
                    .filter(record -> memberName != null && memberName.equals(record.agentName()))
                    .count();
        }
        return new TeamMemberObservation(toolCalls, tokenEstimator.estimate(fullText));
    }
}
