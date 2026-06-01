package com.wzx.babiq.server.agent.team;

import java.util.List;

/**
 * 团队协作审批弹窗需要展示的结构化范围。
 *
 * @param requiresApproval 是否包含写类能力，需要用户显式审批
 * @param description 给审批弹窗展示的人类可读说明
 * @param members 本次团队会启动的成员名
 * @param tools 所有成员工具白名单的并集
 * @param writeScopes 所有成员声明写入范围的并集
 */
public record TeamApprovalScope(
        boolean requiresApproval,
        String description,
        List<String> members,
        List<String> tools,
        List<String> writeScopes
) {
}
