package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.model.ShortTermSummary;
import com.wzx.babiq.server.conversation.items.ThreadItem;

import java.util.List;

/**
 * ContextCompactionService 的运行时请求。
 *
 * @param threadId 所属会话 id
 * @param turnId 触发压缩的 turn id
 * @param providerId 本轮 Provider id
 * @param model 本轮模型名
 * @param historyItems 完整持久化历史 item
 * @param activeSummary 当前窗口已安装的摘要，可为空
 * @param estimatedTokensBefore 压缩前本轮上下文预估 token
 * @param modelContextWindow 模型上下文窗口
 * @param currentUserMessage 当前用户输入，只作为边界提示
 * @param force true 表示用户手动触发，跳过阈值判断但仍沿用同一条审计链路
 * @param triggerType 压缩触发类型，写入审计后用于区分自动、手动或保护性触发
 * @param inputSnapshotId 触发压缩时参考的输入快照 id，可为空；运行时 pre-turn 压缩通常还没有旧快照
 */
public record ContextCompactionRequest(
        String threadId,
        String turnId,
        String providerId,
        String model,
        List<ThreadItem> historyItems,
        ShortTermSummary activeSummary,
        int estimatedTokensBefore,
        int modelContextWindow,
        String currentUserMessage,
        boolean force,
        String triggerType,
        String inputSnapshotId
) {
    /**
     * 自动压缩调用默认不强制，必须达到阈值才执行。
     */
    public ContextCompactionRequest(String threadId,
                                    String turnId,
                                    String providerId,
                                    String model,
                                    List<ThreadItem> historyItems,
                                    ShortTermSummary activeSummary,
                                    int estimatedTokensBefore,
                                    int modelContextWindow,
                                    String currentUserMessage) {
        this(threadId, turnId, providerId, model, historyItems, activeSummary,
                estimatedTokensBefore, modelContextWindow, currentUserMessage, false,
                "AUTO_PRE_TURN", null);
    }

    /**
     * 兼容 P3-3 的强制压缩构造器。
     *
     * <p>手动压缩使用 force=true，因此默认触发类型写成 MANUAL；自动调用仍写 AUTO_PRE_TURN。</p>
     */
    public ContextCompactionRequest(String threadId,
                                    String turnId,
                                    String providerId,
                                    String model,
                                    List<ThreadItem> historyItems,
                                    ShortTermSummary activeSummary,
                                    int estimatedTokensBefore,
                                    int modelContextWindow,
                                    String currentUserMessage,
                                    boolean force) {
        this(threadId, turnId, providerId, model, historyItems, activeSummary,
                estimatedTokensBefore, modelContextWindow, currentUserMessage, force,
                force ? "MANUAL" : "AUTO_PRE_TURN", null);
    }

    public ContextCompactionRequest {
        historyItems = historyItems == null ? List.of() : List.copyOf(historyItems);
        triggerType = triggerType == null || triggerType.isBlank()
                ? (force ? "MANUAL" : "AUTO_PRE_TURN")
                : triggerType;
    }
}
