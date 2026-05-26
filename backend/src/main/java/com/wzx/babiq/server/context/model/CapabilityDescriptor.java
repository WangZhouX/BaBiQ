package com.wzx.babiq.server.context.model;

/**
 * 能力目录摘要。
 *
 * <p>能力目录只描述“有什么能力”，不携带 Spring AI ToolCallback 的完整 schema。
 * 真实可调用 schema 仍通过 Spring AI/SAA tool registry 单独装配，避免工具表挤爆上下文窗口。</p>
 *
 * @param name 能力或工具名称
 * @param source 能力来源，例如 local、mcp:filesystem、skill
 * @param description 给模型阅读的短用途说明
 * @param approvalRequired 调用该能力是否仍需审批
 * @param riskLevel 粗粒度风险等级，用于后续按需装配策略
 */
public record CapabilityDescriptor(
        String name,
        String source,
        String description,
        boolean approvalRequired,
        String riskLevel
) {
}
