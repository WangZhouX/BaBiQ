package com.wzx.babiq.server.api.dto;

/**
 * 运行详情中的审批记录。
 *
 * @param approvalId 审批 id
 * @param toolName 工具名
 * @param argsJson 原始参数 JSON
 * @param editedArgsJson 用户编辑后的参数 JSON
 * @param decision 用户决策
 * @param scope 决策作用域
 * @param status 审批状态
 * @param createdAt 创建时间
 * @param resolvedAt 完成或过期时间
 */
public record RunApprovalDto(
        String approvalId,
        String toolName,
        String argsJson,
        String editedArgsJson,
        String decision,
        String scope,
        String status,
        String createdAt,
        String resolvedAt
) {
}
