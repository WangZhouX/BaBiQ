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
}
