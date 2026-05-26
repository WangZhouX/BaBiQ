package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.model.ShortTermSummary;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.ContextCompactionItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩来源选择器测试。
 *
 * <p>来源选择器只负责决定哪些历史 item 可以进入本次压缩提示词。它必须跳过已经被
 * active summary 覆盖的旧历史，同时排除运行摘要和压缩标记，避免把审计 item 当成用户事实。</p>
 */
class CompactionSourceSelectorTest {

    @Test
    void select_should_skip_items_already_replaced_by_active_summary() {
        CompactionSourceSelector selector = new CompactionSourceSelector();
        List<ThreadItem> history = List.of(
                UserMessageItem.of("it_1", "旧问题"),
                AgentMessageItem.full("it_2", "旧回答"),
                new TurnSummaryItem("it_3", "turnSummary", "COMPLETED", "deepseek-v4-pro", 1, 1, 2, 0, 100),
                new ContextCompactionItem("it_4"),
                UserMessageItem.of("it_5", "后续问题")
        );
        ShortTermSummary activeSummary = new ShortTermSummary(
                "ctxsum_1",
                "it_1..it_2",
                "旧问题和旧回答已经压缩。",
                "it_1",
                "it_2");

        CompactionSource source = selector.select(history, activeSummary);

        assertThat(source.items())
                .extracting(CompactionSourceItem::itemId)
                .containsExactly("it_5");
        assertThat(source.sourceItemRange()).isEqualTo("it_5..it_5");
    }

    @Test
    void select_should_use_only_model_visible_history_when_no_summary_exists() {
        CompactionSourceSelector selector = new CompactionSourceSelector();
        List<ThreadItem> history = List.of(
                UserMessageItem.of("it_1", "旧问题"),
                AgentMessageItem.full("it_2", "旧回答"),
                new TurnSummaryItem("it_3", "turnSummary", "COMPLETED", "deepseek-v4-pro", 1, 1, 2, 0, 100),
                new ContextCompactionItem("it_4"),
                UserMessageItem.of("it_5", "后续问题")
        );

        CompactionSource source = selector.select(history, null);

        assertThat(source.items())
                .extracting(CompactionSourceItem::itemId)
                .containsExactly("it_1", "it_2", "it_5");
        assertThat(source.sourceItemRange()).isEqualTo("it_1..it_5");
    }
}
