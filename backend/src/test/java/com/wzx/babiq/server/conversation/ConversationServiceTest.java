package com.wzx.babiq.server.conversation;

import org.junit.jupiter.api.Test;

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
}
