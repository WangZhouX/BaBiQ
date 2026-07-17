package com.wzx.babiq.server.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.context.model.CapabilityCatalog;
import com.wzx.babiq.server.context.model.CapabilityDescriptor;
import com.wzx.babiq.server.context.model.ContextAssemblyInput;
import com.wzx.babiq.server.context.model.ContextExclusionReason;
import com.wzx.babiq.server.context.model.ContextPriority;
import com.wzx.babiq.server.context.model.ContextSourceType;
import com.wzx.babiq.server.context.model.LongTermMemoryReference;
import com.wzx.babiq.server.context.model.ShortTermSummary;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.ApplicationActionItem;
import com.wzx.babiq.server.conversation.items.ContextCompactionItem;
import com.wzx.babiq.server.conversation.items.ReasoningItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContextAssembler 的第一批验收测试。
 *
 * <p>P3-1 不直接改 ReactAgent 主链路，而是先把“本轮输入、历史、短期摘要、长期记忆、
 * 工作区事实和能力目录”整理成稳定的模型可见视图。这样后续 P3-2 接入 Agent 前，
 * 我们可以独立验证上下文污染边界和 Spring AI message 渲染结果。</p>
 */
class ContextAssemblerTest {

    @Test
    void assemble_should_keep_current_turn_authoritative_and_last_user_message() {
        ContextAssembler assembler = new ContextAssembler(new ObjectMapper(), new ApproximateContextTokenEstimator());

        var result = assembler.assemble(sampleInput());

        assertThat(result.envelope().currentTurn().priority()).isEqualTo(ContextPriority.AUTHORITATIVE);
        assertThat(result.envelope().currentTurn().userMessage()).isEqualTo("请在当前工作区创建 index.html");
        assertThat(result.envelope().recentHistory().priority()).isEqualTo(ContextPriority.HIGH);
        assertThat(result.envelope().longTermMemory().priority()).isEqualTo(ContextPriority.REFERENCE);

        List<Message> messages = result.messages();
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(messages.get(1).getText()).contains("\"current_turn\"").contains("\"capability_catalog\"");
        assertThat(messages.get(2).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(messages.get(2).getText()).isEqualTo("请在当前工作区创建 index.html");
    }

    @Test
    void assemble_should_only_include_model_visible_history_and_snapshot_exclusions() {
        ContextAssembler assembler = new ContextAssembler(new ObjectMapper(), new ApproximateContextTokenEstimator());

        var result = assembler.assemble(sampleInput());

        assertThat(result.envelope().recentHistory().items())
                .extracting("itemId")
                .containsExactly("it_user_1", "it_agent_1", "it_user_2");
        assertThat(result.snapshot().items())
                .filteredOn(item -> item.sourceType() == ContextSourceType.THREAD_ITEM && !item.included())
                .extracting("sourceId")
                .containsExactly("it_reasoning_1", "it_action_1", "it_summary_1", "it_compact_1");
        assertThat(result.snapshot().items())
                .filteredOn(item -> item.sourceId().equals("it_reasoning_1"))
                .extracting("reason")
                .containsExactly(ContextExclusionReason.REASONING_DISPLAY_ONLY.name());
        assertThat(result.snapshot().items())
                .filteredOn(item -> item.sourceId().equals("it_action_1"))
                .extracting("reason")
                .containsExactly(ContextExclusionReason.APPLICATION_ACTION_DISPLAY_ONLY.name());
        assertThat(result.snapshot().items())
                .filteredOn(item -> item.sourceType() == ContextSourceType.THREAD_ITEM && item.included())
                .extracting("sourceId")
                .containsExactly("it_user_1", "it_agent_1", "it_user_2");
    }

    @Test
    void assemble_should_render_capability_catalog_as_reference_not_tool_schema() {
        ContextAssembler assembler = new ContextAssembler(new ObjectMapper(), new ApproximateContextTokenEstimator());

        var result = assembler.assemble(sampleInput());

        assertThat(result.envelope().capabilityCatalog().priority()).isEqualTo(ContextPriority.REFERENCE);
        assertThat(result.envelope().capabilityCatalog().toolSummaries())
                .extracting("name")
                .containsExactly("write_file", "mcp.fs.read_file");
        assertThat(result.messages().get(1).getText())
                .contains("\"tool_summaries\"")
                .doesNotContain("\"input_schema\"");
    }

    /**
     * 构造一份覆盖 P3-1 分层模型的输入。
     *
     * <p>历史里故意混入 TurnSummary 和 ContextCompaction，占位运行 item 必须被排除，
     * 否则模型会把 UI/运行反馈误当作用户事实。</p>
     */
    private ContextAssemblyInput sampleInput() {
        List<ThreadItem> history = List.of(
                UserMessageItem.of("it_user_1", "我之前让你分析项目结构"),
                AgentMessageItem.full("it_agent_1", "已经分析了 backend 和 desktop"),
                new ReasoningItem("it_reasoning_1", "reasoning", "这里是模型思考过程，只给用户看，不进入后续上下文。"),
                new ApplicationActionItem(
                        "it_action_1", "applicationAction", "execution-1", "framework.demo",
                        "读取演示数据", "read_only", "completed", null, null, null, 20L),
                new TurnSummaryItem("it_summary_1", "turnSummary", "COMPLETED", "deepseek-v4-pro", 10, 5, 15, 1, 3000),
                new ContextCompactionItem("it_compact_1"),
                UserMessageItem.of("it_user_2", "后续都使用中文注释")
        );
        CapabilityCatalog catalog = new CapabilityCatalog(List.of(
                new CapabilityDescriptor("write_file", "local", "写入工作区文件", true, "workspace"),
                new CapabilityDescriptor("mcp.fs.read_file", "mcp:fs", "读取 MCP 文件系统内容", true, "mcp")
        ));
        return new ContextAssemblyInput(
                "thr_1",
                "turn_1",
                "请在当前工作区创建 index.html",
                "E:\\BaBiQ",
                "aaa",
                "WORKSPACE_WRITE",
                "ON_REQUEST",
                history,
                new ShortTermSummary("ctxsum_1", "it_old..it_mid", "P2 已验收，P3 进入上下文平台设计"),
                List.of(new LongTermMemoryReference("mem_1", "medium", "用户偏好中文 conventional commit")),
                List.of("当前项目根目录是 E:\\BaBiQ"),
                catalog);
    }
}
