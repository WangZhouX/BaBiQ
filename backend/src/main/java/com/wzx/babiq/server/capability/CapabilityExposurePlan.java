package com.wzx.babiq.server.capability;

import java.util.List;

/**
 * 单轮能力暴露计划。
 *
 * <p>该模型把“模型真实可见工具名”和“审计用能力 id”分开保存。Spring AI 只需要工具名，
 * ContextSnapshot 和运行详情需要能力 id 才能追踪来源。</p>
 *
 * @param visibleCapabilityIds 本轮直接暴露的能力 id
 * @param visibleToolNames 本轮传给 Spring AI/SAA 的工具名
 * @param deferredCapabilityIds 本轮仍保持延迟暴露的能力 id
 * @param disabledCapabilityIds 本轮被禁用或 DISABLED 的能力 id
 * @param reason 计划生成原因，便于写入快照或日志
 */
public record CapabilityExposurePlan(
        List<String> visibleCapabilityIds,
        List<String> visibleToolNames,
        List<String> deferredCapabilityIds,
        List<String> disabledCapabilityIds,
        String reason
) {

    /**
     * 防御性复制集合字段。
     */
    public CapabilityExposurePlan {
        visibleCapabilityIds = visibleCapabilityIds == null ? List.of() : List.copyOf(visibleCapabilityIds);
        visibleToolNames = visibleToolNames == null ? List.of() : List.copyOf(visibleToolNames);
        deferredCapabilityIds = deferredCapabilityIds == null ? List.of() : List.copyOf(deferredCapabilityIds);
        disabledCapabilityIds = disabledCapabilityIds == null ? List.of() : List.copyOf(disabledCapabilityIds);
    }
}
