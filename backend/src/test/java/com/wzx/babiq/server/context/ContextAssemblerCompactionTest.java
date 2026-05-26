package com.wzx.babiq.server.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.context.model.CapabilityCatalog;
import com.wzx.babiq.server.context.model.ContextAssemblyInput;
import com.wzx.babiq.server.context.model.ContextExclusionReason;
import com.wzx.babiq.server.context.model.ContextSourceType;
import com.wzx.babiq.server.context.model.ShortTermSummary;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContextAssembler 与短期压缩摘要协作测试。
 *
 * <p>P3-3 的关键语义是“摘要替换旧窗口”，不是把摘要追加到完整历史后面。
 * 因此被 active summary 覆盖的历史必须从 recent_history 中移除，并在 snapshot 中留下排除原因。</p>
 */
class ContextAssemblerCompactionTest {

    @Test
    void assemble_should_replace_history_covered_by_active_summary() {
        ContextAssembler assembler = new ContextAssembler(new ObjectMapper(), text -> text == null ? 0 : 1);
        List<ThreadItem> history = List.of(
                UserMessageItem.of("it_1", "旧问题"),
                AgentMessageItem.full("it_2", "旧回答"),
                UserMessageItem.of("it_3", "新的背景")
        );
        ShortTermSummary activeSummary = new ShortTermSummary(
                "ctxsum_1",
                "it_1..it_2",
                "旧问题和旧回答已经压缩为摘要。",
                "it_1",
                "it_2");

        var result = assembler.assemble(new ContextAssemblyInput(
                "thr_1",
                "turn_1",
                "当前问题",
                "E:\\BaBiQ",
                "BaBiQ",
                "WORKSPACE_WRITE",
                "ON_REQUEST",
                history,
                activeSummary,
                List.of(),
                List.of(),
                new CapabilityCatalog(List.of())));

        assertThat(result.envelope().shortTermSummary().summaryId()).isEqualTo("ctxsum_1");
        assertThat(result.envelope().recentHistory().items())
                .extracting("itemId")
                .containsExactly("it_3");
        assertThat(result.snapshot().items())
                .filteredOn(item -> item.sourceType() == ContextSourceType.THREAD_ITEM && !item.included())
                .extracting("sourceId", "reason")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("it_1", ContextExclusionReason.REPLACED_BY_SUMMARY.name()),
                        org.assertj.core.groups.Tuple.tuple("it_2", ContextExclusionReason.REPLACED_BY_SUMMARY.name()));
    }
}
