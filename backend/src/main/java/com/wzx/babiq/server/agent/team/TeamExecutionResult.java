package com.wzx.babiq.server.agent.team;

/**
 * 团队协作执行结果。
 *
 * @param status completed 或 failed
 * @param summary 给父 Agent 的短摘要
 * @param round 实际调度轮数
 * @param currentAgent 最后调度的成员
 */
public record TeamExecutionResult(
        String status,
        String summary,
        int round,
        String currentAgent
) {
}
