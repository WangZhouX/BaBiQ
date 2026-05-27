package com.wzx.babiq.server.capability;

import java.time.Instant;

/**
 * 能力目录记录。
 *
 * <p>它是 `bq_capabilities` 的领域模型，不包含完整 tool input schema，
 * 只保存可搜索、可展示、可审计的元数据。真实 schema 仍由 Spring AI ToolCallback 通道承载。</p>
 *
 * @param capabilityId 稳定能力 id，例如 local.exec_shell
 * @param type 能力类型
 * @param namespace 命名空间，local、mcp server id 或 skill 分组
 * @param name 工具或 skill 短名称
 * @param displayName 桌面端展示名称
 * @param description 给模型和用户看的说明
 * @param sourceId 来源 id，local/MCP server/skill directory
 * @param schemaHash schema 或正文摘要 hash
 * @param searchText 搜索索引文本
 * @param exposureMode 暴露模式
 * @param enabled 用户是否启用
 * @param lastSeenAt 最近扫描时间
 */
public record CapabilityDescriptor(
        String capabilityId,
        CapabilityType type,
        String namespace,
        String name,
        String displayName,
        String description,
        String sourceId,
        String schemaHash,
        String searchText,
        CapabilityExposureMode exposureMode,
        boolean enabled,
        Instant lastSeenAt
) {
}
