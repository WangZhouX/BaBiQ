package com.wzx.babiq.server.api.dto;

/**
 * 团队成员在右侧面板中的聚合状态。
 *
 * @param teamId 所属团队 id
 * @param memberId 成员协议 id
 * @param name 成员技术名
 * @param displayName 展示名
 * @param role 角色
 * @param mode 工具权限模式
 * @param toolNames 工具白名单摘要
 * @param status 成员状态
 * @param memberOrder 展示排序
 * @param toolCallCount 工具调用次数
 * @param tokenEstimate token 粗估
 * @param summary 成员摘要
 */
public record TeamMemberInfo(
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
