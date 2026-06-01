package com.wzx.babiq.server.agent.team;

/**
 * 团队成员聚合状态记录。
 *
 * @param teamId 所属团队 id
 * @param memberId 协议层成员 id
 * @param name 成员 ASCII 技术名
 * @param displayName 桌面端展示名
 * @param role 成员角色
 * @param mode 成员委派模式
 * @param toolNames 成员工具白名单，逗号分隔
 * @param status 成员状态
 * @param memberOrder 成员排序号
 * @param toolCallCount 成员聚合工具调用次数
 * @param tokenEstimate 成员 token 粗估值
 * @param summary 成员短摘要
 */
public record TeamMemberRecord(
        String teamId,
        String memberId,
        String name,
        String displayName,
        String role,
        String mode,
        String toolNames,
        String status,
        int memberOrder,
        int toolCallCount,
        int tokenEstimate,
        String summary
) {
}
