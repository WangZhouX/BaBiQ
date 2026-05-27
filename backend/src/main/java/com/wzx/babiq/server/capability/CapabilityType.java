package com.wzx.babiq.server.capability;

/**
 * BaBiQ 能力类型。
 *
 * <p>能力目录统一管理 local tool、MCP tool 和本地 Skill，但三者执行路径不同。
 * 类型枚举让 Planner 能按来源决定默认暴露策略，并让桌面端清楚展示来源。</p>
 */
public enum CapabilityType {
    /** BaBiQ 后端内置 Spring AI 工具。 */
    LOCAL_TOOL,
    /** 通过 P2-6 MCP Client 动态发现的外部工具。 */
    MCP_TOOL,
    /** 本地受控 Skill 目录中的元数据和正文。 */
    SKILL
}
