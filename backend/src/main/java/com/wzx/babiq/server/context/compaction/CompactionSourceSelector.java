package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.model.ShortTermSummary;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 短期压缩来源选择器。
 *
 * <p>它和 ContextAssembler 共享同一个污染边界：只有完整 user/assistant 文本可以进入模型。
 * 如果当前 window 已经安装 active summary，则摘要覆盖范围内的旧 item 不会再次进入压缩，避免重复摘要。</p>
 */
@Component
public class CompactionSourceSelector {

    /**
     * 从完整历史中选择本次压缩来源。
     *
     * @param historyItems 持久化 ThreadItem 历史
     * @param activeSummary 当前已安装的短期摘要，可为空
     * @return 可交给压缩策略的来源集合
     */
    public CompactionSource select(List<ThreadItem> historyItems, ShortTermSummary activeSummary) {
        List<ThreadItem> history = historyItems == null ? List.of() : historyItems;
        String coveredEndItemId = activeSummary == null ? null : activeSummary.sourceEndItemId();
        boolean canSkipCoveredRange = coveredEndItemId != null && history.stream()
                .anyMatch(item -> coveredEndItemId.equals(item.id()));
        boolean skipUntilCoveredEnd = canSkipCoveredRange;
        List<CompactionSourceItem> selected = new ArrayList<>();
        for (ThreadItem item : history) {
            if (skipUntilCoveredEnd) {
                if (coveredEndItemId.equals(item.id())) {
                    skipUntilCoveredEnd = false;
                }
                continue;
            }
            addVisibleItem(selected, item);
        }
        String startId = selected.isEmpty() ? null : selected.getFirst().itemId();
        String endId = selected.isEmpty() ? null : selected.getLast().itemId();
        String range = startId == null ? "" : startId + ".." + endId;
        return new CompactionSource(selected, range, startId, endId);
    }

    /**
     * 只收集模型可见的完整对话文本，运行反馈和压缩标记天然落空。
     */
    private void addVisibleItem(List<CompactionSourceItem> selected, ThreadItem item) {
        if (item instanceof UserMessageItem userMessageItem && hasText(userMessageItem.text())) {
            selected.add(new CompactionSourceItem(userMessageItem.id(), "user", userMessageItem.text()));
        } else if (item instanceof AgentMessageItem agentMessageItem && hasText(agentMessageItem.text())) {
            selected.add(new CompactionSourceItem(agentMessageItem.id(), "assistant", agentMessageItem.text()));
        }
    }

    /**
     * @return true 表示文本可以进入压缩提示词
     */
    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
