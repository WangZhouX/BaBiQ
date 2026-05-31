package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadItemJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void user_message_should_serialize_with_type_tag() throws Exception {
        ThreadItem item = new UserMessageItem("it_01", "userMessage", "hi");

        String json = objectMapper.writeValueAsString(item);

        assertThat(json)
                .contains("\"type\":\"userMessage\"")
                .contains("\"text\":\"hi\"");
    }

    @Test
    void agent_message_should_support_text_delta() throws Exception {
        ThreadItem item = AgentMessageItem.delta("it_02", "hello");

        String json = objectMapper.writeValueAsString(item);

        assertThat(json)
                .contains("\"type\":\"agentMessage\"")
                .contains("\"textDelta\":\"hello\"")
                .doesNotContain("\"text\":");
    }

    @Test
    void polymorphic_deserialization_should_use_existing_type_tag() throws Exception {
        String json = "{\"id\":\"it_01\",\"type\":\"userMessage\",\"text\":\"hi\"}";

        ThreadItem item = objectMapper.readValue(json, ThreadItem.class);

        assertThat(item).isInstanceOf(UserMessageItem.class);
        assertThat(((UserMessageItem) item).text()).isEqualTo("hi");
    }

    @Test
    void all_placeholder_items_should_serialize_with_required_type() throws Exception {
        ThreadItem item = new ContextCompactionItem("it_12");

        String json = objectMapper.writeValueAsString(item);

        assertThat(json)
                .contains("\"id\":\"it_12\"")
                .contains("\"type\":\"contextCompaction\"");
    }

    @Test
    void turn_summary_should_serialize_metrics_and_deserialize_by_type_tag() throws Exception {
        ThreadItem item = new TurnSummaryItem(
                "it_13", "turnSummary", "completed", "qwen-plus",
                100L, 50L, 150L, 2, 1200L);

        String json = objectMapper.writeValueAsString(item);
        ThreadItem restored = objectMapper.readValue(json, ThreadItem.class);

        assertThat(json)
                .contains("\"type\":\"turnSummary\"")
                .contains("\"status\":\"completed\"")
                .contains("\"model\":\"qwen-plus\"")
                .contains("\"totalTokens\":150")
                .contains("\"toolCalls\":2")
                .doesNotContain("estimatedCostUsd")
                .contains("\"durationMs\":1200");
        assertThat(restored).isInstanceOf(TurnSummaryItem.class);
    }

    @Test
    void plan_item_should_serialize_step_status_and_active_form() throws Exception {
        ThreadItem item = new PlanItem(
                "it_plan_1",
                "plan",
                null,
                java.util.List.of(
                        new PlanItem.PlanStep(1, "阅读计划文档", "completed", null),
                        new PlanItem.PlanStep(2, "实现计划工具", "in_progress", "正在实现计划工具")),
                "拆成后端协议和桌面 UI 两段推进");

        String json = objectMapper.writeValueAsString(item);
        ThreadItem restored = objectMapper.readValue(json, ThreadItem.class);

        assertThat(json)
                .contains("\"type\":\"plan\"")
                .contains("\"status\":\"completed\"")
                .contains("\"activeForm\":\"正在实现计划工具\"")
                .doesNotContain("\"goal\":null");
        assertThat(restored).isInstanceOf(PlanItem.class);
        PlanItem plan = (PlanItem) restored;
        assertThat(plan.goal()).isNull();
        assertThat(plan.steps()).extracting(PlanItem.PlanStep::status)
                .containsExactly("completed", "in_progress");
        assertThat(plan.steps().get(1).activeForm()).isEqualTo("正在实现计划工具");
    }

    @Test
    void agent_delegation_item_should_serialize_and_deserialize_by_type_tag() throws Exception {
        ThreadItem item = new AgentDelegationItem(
                "it_delegate_1",
                "agentDelegation",
                "dlg_1",
                "babiq_agent",
                "explorer",
                "completed",
                "READ_ONLY_TOOL",
                "read README and summarize entry points",
                2,
                128);

        String json = objectMapper.writeValueAsString(item);
        ThreadItem restored = objectMapper.readValue(json, ThreadItem.class);

        assertThat(json)
                .contains("\"type\":\"agentDelegation\"")
                .contains("\"delegationId\":\"dlg_1\"")
                .contains("\"childAgent\":\"explorer\"")
                .contains("\"mode\":\"READ_ONLY_TOOL\"")
                .contains("\"toolCallCount\":2")
                .contains("\"tokenEstimate\":128");
        assertThat(restored).isInstanceOf(AgentDelegationItem.class);
        AgentDelegationItem delegation = (AgentDelegationItem) restored;
        assertThat(delegation.parentAgent()).isEqualTo("babiq_agent");
        assertThat(delegation.summary()).contains("README");
    }
}
