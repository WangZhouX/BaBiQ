package com.wzx.babiq.server.conversation;

import com.wzx.babiq.server.conversation.items.CommandExecutionItem;
import com.wzx.babiq.server.conversation.items.FileChangeItem;
import com.wzx.babiq.server.conversation.items.ReasoningItem;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationServiceTest {

    @Test
    void create_thread_should_return_unique_thread_id() {
        ConversationService conversationService = new ConversationService();

        Thread firstThread = conversationService.createThread(".");
        Thread secondThread = conversationService.createThread(".");

        assertThat(firstThread.id()).startsWith("thr_");
        assertThat(secondThread.id()).startsWith("thr_");
        assertThat(firstThread.id()).isNotEqualTo(secondThread.id());
    }

    @Test
    void start_turn_should_attach_to_existing_thread() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");

        Turn turn = conversationService.startTurn(thread.id());

        assertThat(turn.id()).startsWith("turn_");
        assertThat(turn.threadId()).isEqualTo(thread.id());
        assertThat(turn.status()).isEqualTo(TurnStatus.CREATED);
    }

    @Test
    void start_turn_with_unknown_thread_should_throw() {
        ConversationService conversationService = new ConversationService();

        assertThatThrownBy(() -> conversationService.startTurn("thr_missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threadId=thr_missing");
    }

    @Test
    void lookup_thread_and_turn_should_return_optional() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        Turn turn = conversationService.startTurn(thread.id());

        assertThat(conversationService.findThread(thread.id())).contains(thread);
        assertThat(conversationService.findTurn(turn.id())).contains(turn);
        assertThat(conversationService.findThread("thr_missing")).isEmpty();
        assertThat(conversationService.findTurn("turn_missing")).isEmpty();
    }

    @Test
    void helper_methods_should_create_protocol_items_with_stable_type_tags() {
        ConversationService conversationService = new ConversationService();

        CommandExecutionItem commandItem = conversationService.emitCommandExecution(
                "hostname", "completed", 0, "pc", "", 12L);
        FileChangeItem fileItem = conversationService.emitFileChange(
                "write", "hello.txt", "denied", "Sandbox is read-only");
        ReasoningItem reasoningItem = conversationService.emitReasoning("准备读取 README");

        assertThat(commandItem.type()).isEqualTo("commandExecution");
        assertThat(commandItem.id()).startsWith("it_");
        assertThat(commandItem.status()).isEqualTo("completed");
        assertThat(fileItem.type()).isEqualTo("fileChange");
        assertThat(fileItem.status()).isEqualTo("denied");
        assertThat(reasoningItem.type()).isEqualTo("reasoning");
        assertThat(reasoningItem.text()).contains("README");
    }

    @Test
    void emit_turn_summary_should_create_protocol_item_with_usage_cost_and_duration() {
        ConversationService conversationService = new ConversationService();

        TurnSummaryItem item = conversationService.emitTurnSummary(
                "completed", "qwen-plus", 100L, 50L, 150L, 2, new BigDecimal("0.0014"), 1200L);

        assertThat(item.id()).startsWith("it_");
        assertThat(item.type()).isEqualTo("turnSummary");
        assertThat(item.status()).isEqualTo("completed");
        assertThat(item.model()).isEqualTo("qwen-plus");
        assertThat(item.totalTokens()).isEqualTo(150L);
        assertThat(item.toolCalls()).isEqualTo(2);
        assertThat(item.estimatedCostUsd()).isEqualByComparingTo("0.0014");
        assertThat(item.durationMs()).isEqualTo(1200L);
    }
}
