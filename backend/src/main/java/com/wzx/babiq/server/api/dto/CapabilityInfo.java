package com.wzx.babiq.server.api.dto;

/**
 * 桌面端展示用的能力摘要。
 *
 * @param capabilityId 稳定能力 id，后端用它更新开关和暴露模式
 * @param type 能力类型，例如本地工具、MCP 工具或 Skill
 * @param namespace 能力命名空间，用于 UI 分组和排查来源
 * @param name 工具或 Skill 的短名称
 * @param displayName 面向用户的展示名称
 * @param description 能力说明，来自 ToolDefinition、MCP metadata 或 Skill front matter
 * @param exposureMode 当前暴露模式，决定是否默认进入模型工具列表
 * @param enabled 用户是否启用该能力
 * @param lastSeenAt 最近一次目录同步看到该能力的时间
 */
public record CapabilityInfo(
        String capabilityId,
        String type,
        String namespace,
        String name,
        String displayName,
        String description,
        String exposureMode,
        boolean enabled,
        String lastSeenAt
) {
}
