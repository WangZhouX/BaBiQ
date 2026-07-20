package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.attachment.AttachmentMetadata;
import com.wzx.babiq.server.attachment.AttachmentSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(((UserMessageItem) item).attachments()).isEmpty();
    }

    @Test
    void user_message_should_round_trip_attachment_metadata_without_file_content() throws Exception {
        AttachmentMetadata attachment = new AttachmentMetadata(
                "550e8400-e29b-41d4-a716-446655440000",
                "A-7K3M2Q",
                "合同.pdf",
                "C:\\business\\合同.pdf",
                "application/pdf",
                4096,
                "a".repeat(64),
                AttachmentSource.SELECTED_FILE);
        ThreadItem item = new UserMessageItem(
                "it_attachment",
                "userMessage",
                "审阅合同",
                List.of(attachment));

        String json = objectMapper.writeValueAsString(item);
        ThreadItem restored = objectMapper.readValue(json, ThreadItem.class);

        assertThat(json)
                .contains("\"attachments\"")
                .contains("\"displayId\":\"A-7K3M2Q\"")
                .contains("\"localPath\":\"C:\\\\business\\\\合同.pdf\"")
                .doesNotContain("base64", "contentBytes", "extractedText");
        assertThat(restored).isInstanceOf(UserMessageItem.class);
        UserMessageItem userMessage = (UserMessageItem) restored;
        assertThat(userMessage.attachments()).containsExactly(attachment);
        assertThatThrownBy(() -> userMessage.attachments().add(attachment))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void user_message_should_normalize_explicit_null_attachments_to_immutable_empty_list() throws Exception {
        UserMessageItem restored = objectMapper.readValue(
                "{\"id\":\"it_null\",\"type\":\"userMessage\",\"text\":\"hi\",\"attachments\":null}",
                UserMessageItem.class);

        assertThat(restored.attachments()).isEmpty();
        assertThatThrownBy(() -> restored.attachments().add(new AttachmentMetadata(
                "550e8400-e29b-41d4-a716-446655440000",
                "A-7K3M2Q",
                "合同.pdf",
                "C:\\business\\合同.pdf",
                "application/pdf",
                1,
                "a".repeat(64),
                AttachmentSource.SELECTED_FILE)))
                .isInstanceOf(UnsupportedOperationException.class);
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

    @Test
    void orchestration_item_should_serialize_topology_and_node_status() throws Exception {
        ThreadItem item = new OrchestrationItem(
                "it_orch_1",
                "orchestration",
                "orch_1",
                "整理项目",
                "parallel",
                "running",
                "两个节点并行执行",
                true,
                true,
                "{\"root\":{\"groupId\":\"g_root\",\"topology\":\"parallel\",\"children\":[{\"nodeId\":\"node_scan\"},{\"nodeId\":\"node_write\"}]}}",
                java.util.List.of(
                        new OrchestrationItem.NodeStatus(
                                "node_scan", "scan", "读取节点", "completed", "READ_ONLY_TOOL",
                                "查看目录", "deepseek-v4-pro", 2, 128, "已读取目录"),
                        new OrchestrationItem.NodeStatus(
                                "node_write", "write", "写入节点", "running", "WORKSPACE_TOOL",
                                "写入总结", "deepseek-v4-pro", 1, 64, "正在写入")));

        String json = objectMapper.writeValueAsString(item);
        ThreadItem restored = objectMapper.readValue(json, ThreadItem.class);

        assertThat(json)
                .contains("\"type\":\"orchestration\"")
                .contains("\"orchestrationId\":\"orch_1\"")
                .contains("\"topology\":\"parallel\"")
                .contains("\"frozen\":true")
                .contains("\"structureJson\"")
                .contains("\"nodeId\":\"node_scan\"")
                .contains("\"mode\":\"WORKSPACE_TOOL\"");
        assertThat(restored).isInstanceOf(OrchestrationItem.class);
        OrchestrationItem orchestration = (OrchestrationItem) restored;
        assertThat(orchestration.nodes()).hasSize(2);
        assertThat(orchestration.nodes().get(1).summary()).contains("写入");
    }

    @Test
    void applicationActionItemRoundTripsOnlySafeDisplayFields() throws Exception {
        ThreadItem item = new ApplicationActionItem(
                "it_action_1", "applicationAction", "execution-1", "framework.demo",
                "更新案件资料", "reversible_write", "previewed",
                "将更新 2 个字段", null, null, 18L);

        String serialized = objectMapper.writeValueAsString(item);
        ThreadItem restored = objectMapper.readValue(serialized, ThreadItem.class);

        assertThat(restored).isInstanceOf(ApplicationActionItem.class);
        assertThat(serialized)
                .contains("\"type\":\"applicationAction\"")
                .contains("\"executionId\":\"execution-1\"")
                .contains("\"status\":\"previewed\"")
                .doesNotContain("input", "output", "identity", "permission", "secret");
    }
}
