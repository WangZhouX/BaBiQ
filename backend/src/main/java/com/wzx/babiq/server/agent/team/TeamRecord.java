package com.wzx.babiq.server.agent.team;

/**
 * 团队协作整体运行记录。
 *
 * @param teamId 协议层团队 id
 * @param threadId 所属 thread id；工具上下文缺失时可为空
 * @param turnId 所属 turn id；手动直发消息可能为空
 * @param title 用户可读标题
 * @param goal 团队整体目标
 * @param status 团队状态：pending、running、completed、failed
 * @param cwd 执行时工作目录快照
 * @param sandboxMode 执行时沙箱模式快照
 * @param approved 是否已通过运行前整体审批
 * @param frozen 是否已冻结成员和工具范围
 * @param maxRounds supervisor 最多调度轮数
 * @param currentRound 当前调度轮数
 * @param currentAgent 当前或最近被调度成员
 * @param summary 团队短摘要
 * @param errorMessage 失败原因；成功或运行中为空
 */
public record TeamRecord(
        String teamId,
        String threadId,
        String turnId,
        String title,
        String goal,
        String status,
        String cwd,
        String sandboxMode,
        boolean approved,
        boolean frozen,
        int maxRounds,
        int currentRound,
        String currentAgent,
        String summary,
        String errorMessage
) {
}
