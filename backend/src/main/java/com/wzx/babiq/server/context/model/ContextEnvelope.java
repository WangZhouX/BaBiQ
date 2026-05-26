package com.wzx.babiq.server.context.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 分层上下文 envelope。
 *
 * <p>该对象是 P3-1 的核心领域模型。它把 Codex 风格的 prompt slot 思路显式化：
 * current_turn 是权威层，recent_history 是高优先级历史，summary/memory/workspace/capability
 * 都是有边界的参考层。</p>
 *
 * @param currentTurn 本轮权威输入
 * @param recentHistory 近期模型可见历史
 * @param shortTermSummary 短期摘要层，可为空
 * @param longTermMemory 长期记忆参考层
 * @param workspaceContext 工作区事实层
 * @param capabilityCatalog 能力目录层
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextEnvelope(
        CurrentTurn currentTurn,
        RecentHistory recentHistory,
        ShortTermSummarySection shortTermSummary,
        LongTermMemorySection longTermMemory,
        WorkspaceContext workspaceContext,
        CapabilityCatalogSection capabilityCatalog
) {

    /**
     * 本轮权威上下文。
     *
     * @param priority 固定为 AUTHORITATIVE
     * @param threadId 当前业务会话 id
     * @param turnId 当前 turn id
     * @param userMessage 本轮用户消息
     * @param cwd 本轮工作目录
     * @param projectId 当前项目 id，可为空
     * @param sandboxPolicy 本轮沙箱策略快照
     * @param approvalPolicy 本轮审批策略快照
     */
    public record CurrentTurn(
            ContextPriority priority,
            String threadId,
            String turnId,
            String userMessage,
            String cwd,
            String projectId,
            String sandboxPolicy,
            String approvalPolicy
    ) {
    }

    /**
     * 近期历史上下文。
     *
     * @param priority 固定为 HIGH
     * @param items 过滤后的 user/assistant 历史
     */
    public record RecentHistory(
            ContextPriority priority,
            List<RecentHistoryItem> items
    ) {
        public RecentHistory {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * 短期摘要上下文。
     *
     * @param priority 固定为 MEDIUM
     * @param summaryId 摘要 id
     * @param sourceItemRange 来源 item 范围
     * @param summary 摘要正文
     */
    public record ShortTermSummarySection(
            ContextPriority priority,
            String summaryId,
            String sourceItemRange,
            String summary
    ) {
    }

    /**
     * 长期记忆上下文。
     *
     * @param priority 固定为 REFERENCE
     * @param memoryRefs 长期记忆引用
     */
    public record LongTermMemorySection(
            ContextPriority priority,
            List<LongTermMemoryReference> memoryRefs
    ) {
        public LongTermMemorySection {
            memoryRefs = memoryRefs == null ? List.of() : List.copyOf(memoryRefs);
        }
    }

    /**
     * 工作区事实上下文。
     *
     * @param priority 固定为 REFERENCE
     * @param facts 由系统确认的事实，不包含模型推断
     */
    public record WorkspaceContext(
            ContextPriority priority,
            List<String> facts
    ) {
        public WorkspaceContext {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
    }

    /**
     * 能力目录上下文。
     *
     * @param priority 固定为 REFERENCE
     * @param toolSummaries 工具或能力摘要，不包含 input schema
     */
    public record CapabilityCatalogSection(
            ContextPriority priority,
            List<CapabilityDescriptor> toolSummaries
    ) {
        public CapabilityCatalogSection {
            toolSummaries = toolSummaries == null ? List.of() : List.copyOf(toolSummaries);
        }
    }
}
