package com.wzx.babiq.server.api.dto;

/**
 * 团队运行列表和详情共用的摘要信息。
 *
 * @param teamId 团队协议 id
 * @param threadId 所属会话 id
 * @param turnId 所属运行 turn id
 * @param title 团队标题
 * @param goal 团队当前目标
 * @param status 团队状态
 * @param cwd 执行目录快照
 * @param sandboxMode 沙箱模式快照
 * @param approved 是否已通过运行前整体审批
 * @param frozen 是否已冻结成员和工具范围
 * @param maxRounds 最大调度轮数
 * @param currentRound 当前调度轮数
 * @param currentAgent 当前或最近调度成员
 * @param summary 团队摘要
 * @param errorMessage 失败原因
 * @param memberCount 成员数量
 */
public record TeamInfo(
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
        String errorMessage,
        int memberCount
) {
}
