package com.wzx.babiq.server.context.model;

import com.wzx.babiq.server.conversation.items.ThreadItem;

import java.util.List;

/**
 * ContextAssembler 的输入模型。
 *
 * <p>该 record 把本轮 turn、历史、摘要、记忆、工作区事实和能力目录显式分层传入，
 * 避免后续实现从多个全局状态临时拼 prompt，降低污染和不可解释风险。</p>
 *
 * @param threadId 当前业务会话 id
 * @param turnId 当前 turn id
 * @param currentUserMessage 本轮用户消息，是最高优先级输入
 * @param cwd 本轮工作目录，由 thread/start 或设置页决定
 * @param projectId 当前项目 id，可为空
 * @param sandboxPolicy 本轮沙箱策略快照
 * @param approvalPolicy 本轮审批策略快照
 * @param historyItems 已持久化的历史 ThreadItem，ContextAssembler 会做模型可见性过滤
 * @param shortTermSummary 可选短期摘要，P3-1 只装配不生成
 * @param longTermMemoryReferences 可选长期记忆引用，必须作为参考层
 * @param workspaceFacts 当前工作区事实，不能放模型推测
 * @param capabilityCatalog 能力目录摘要，不等同于真实可调用 tool schema
 */
public record ContextAssemblyInput(
        String threadId,
        String turnId,
        String currentUserMessage,
        String cwd,
        String projectId,
        String sandboxPolicy,
        String approvalPolicy,
        List<ThreadItem> historyItems,
        ShortTermSummary shortTermSummary,
        List<LongTermMemoryReference> longTermMemoryReferences,
        List<String> workspaceFacts,
        CapabilityCatalog capabilityCatalog
) {

    /**
     * 规整集合字段，保证后续 assembler 不需要重复做 null 防御。
     */
    public ContextAssemblyInput {
        historyItems = historyItems == null ? List.of() : List.copyOf(historyItems);
        longTermMemoryReferences = longTermMemoryReferences == null ? List.of() : List.copyOf(longTermMemoryReferences);
        workspaceFacts = workspaceFacts == null ? List.of() : List.copyOf(workspaceFacts);
        capabilityCatalog = capabilityCatalog == null ? new CapabilityCatalog(List.of()) : capabilityCatalog;
    }
}
