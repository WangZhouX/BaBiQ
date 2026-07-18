package com.wzx.babiq.server.conversation;

import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.conversation.items.CommandExecutionItem;
import com.wzx.babiq.server.conversation.items.FileChangeItem;
import com.wzx.babiq.server.conversation.items.ReasoningItem;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void unscopedLookupDoesNotExposeBusinessThreadOrTurnFromMemory() {
        ConversationService service = new ConversationService();
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "session", "auth", 1, "user", "tenant", "platform");
        Thread thread = service.createThread("C:/business", scope);
        Turn turn = service.startTurn(thread.id(), scope);

        assertThat(service.findThread(thread.id())).isEmpty();
        assertThat(service.findTurn(turn.id())).isEmpty();
        assertThat(service.hasActiveTurn(thread.id())).isFalse();
        assertThat(service.findThread(thread.id(), scope)).contains(thread);
        assertThat(service.findTurn(turn.id(), scope)).contains(turn);
        assertThat(service.hasActiveTurn(thread.id(), scope)).isTrue();
    }

    @Test
    void databaseFallbackRestoresPersistedTurnStatusForScopedAndUnscopedLookups() {
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        ConversationService service = new ConversationService(null, persistence, null, null);
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth", 3, "user", "tenant", "platform");
        TurnEntity completed = persistedTurn("turn-completed", "COMPLETED");
        TurnEntity waiting = persistedTurn("turn-waiting", "WAITING_APPROVAL");
        when(persistence.findTurn("turn-completed")).thenReturn(java.util.Optional.of(completed));
        when(persistence.findTurn("turn-waiting", scope)).thenReturn(java.util.Optional.of(waiting));

        assertThat(service.findTurn("turn-completed")).get()
                .extracting(Turn::status)
                .isEqualTo(TurnStatus.COMPLETED);
        assertThat(service.findTurn("turn-waiting", scope)).get()
                .extracting(Turn::status)
                .isEqualTo(TurnStatus.WAITING_APPROVAL);
    }

    @Test
    void identityChangeExpiresOnlyExactScopePreExecutionTurnsAndLeavesRunningTurnUntouched() {
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        ConversationService service = new ConversationService(null, persistence, null, null);
        BusinessIdentityScope oldScope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth-old", 3, "user-old", "tenant-old", "platform");
        BusinessIdentityScope newScope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth-new", 4, "user-new", "tenant-new", "platform");
        Thread oldThread = service.createThread("C:/business", oldScope);
        Turn created = service.startTurn(oldThread.id(), oldScope);
        Turn waiting = service.startTurn(oldThread.id(), oldScope);
        waiting.start();
        waiting.waitApproval();
        Turn running = service.startTurn(oldThread.id(), oldScope);
        running.start();
        Thread secondOldThread = service.createThread("C:/business-second", oldScope);
        Turn secondCreated = service.startTurn(secondOldThread.id(), oldScope);
        Thread newThread = service.createThread("C:/business", newScope);
        Turn newCreated = service.startTurn(newThread.id(), newScope);
        when(persistence.expirePreExecutionTurn(created.id(), oldScope, "business identity changed"))
                .thenReturn(true);
        when(persistence.expirePreExecutionTurn(waiting.id(), oldScope, "business identity changed"))
                .thenReturn(true);
        when(persistence.expirePreExecutionTurn(secondCreated.id(), oldScope, "business identity changed"))
                .thenReturn(true);

        assertThat(service.expirePreExecutionTurns(oldScope, "business identity changed"))
                .containsExactlyInAnyOrder(oldThread.id(), secondOldThread.id());

        assertThat(created.status()).isEqualTo(TurnStatus.EXPIRED);
        assertThat(waiting.status()).isEqualTo(TurnStatus.EXPIRED);
        assertThat(running.status()).isEqualTo(TurnStatus.RUNNING);
        assertThat(secondCreated.status()).isEqualTo(TurnStatus.EXPIRED);
        assertThat(newCreated.status()).isEqualTo(TurnStatus.CREATED);
        verify(persistence).expirePreExecutionTurn(created.id(), oldScope, "business identity changed");
        verify(persistence).expirePreExecutionTurn(waiting.id(), oldScope, "business identity changed");
        verify(persistence).expirePreExecutionTurn(secondCreated.id(), oldScope, "business identity changed");
        verify(persistence, never()).expirePreExecutionTurn(running.id(), oldScope, "business identity changed");
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
    void emit_turn_summary_should_create_protocol_item_with_usage_and_duration() {
        ConversationService conversationService = new ConversationService();

        TurnSummaryItem item = conversationService.emitTurnSummary(
                "completed", "qwen-plus", 100L, 50L, 150L, 2, 1200L);

        assertThat(item.id()).startsWith("it_");
        assertThat(item.type()).isEqualTo("turnSummary");
        assertThat(item.status()).isEqualTo("completed");
        assertThat(item.model()).isEqualTo("qwen-plus");
        assertThat(item.totalTokens()).isEqualTo(150L);
        assertThat(item.toolCalls()).isEqualTo(2);
        assertThat(item.durationMs()).isEqualTo(1200L);
    }

    @Test
    void failTurnIfRunningUpdatesPersistenceBeforeTheSameInMemoryTurn() {
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        ConversationService service = new ConversationService(null, persistence, null, null);
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth", 3, "user", "tenant", "platform");
        Thread thread = service.createThread("C:/business", scope);
        Turn turn = service.startTurn(thread.id(), scope);
        turn.start();
        when(persistence.failRunningTurn(turn.id(), scope, "resume_submission_failed")).thenReturn(true);

        assertThat(service.failTurnIfRunning(turn, "resume_submission_failed")).isTrue();

        verify(persistence).failRunningTurn(turn.id(), scope, "resume_submission_failed");
        assertThat(turn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(turn.failureReason()).isEqualTo("resume_submission_failed");
    }

    @Test
    void failTurnIfRunningDoesNotDivergeMemoryWhenPersistenceCasLoses() {
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        ConversationService service = new ConversationService(null, persistence, null, null);
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth", 3, "user", "tenant", "platform");
        Thread thread = service.createThread("C:/business", scope);
        Turn turn = service.startTurn(thread.id(), scope);
        turn.start();
        when(persistence.failRunningTurn(turn.id(), scope, "resume_submission_failed")).thenReturn(false);

        assertThat(service.failTurnIfRunning(turn, "resume_submission_failed")).isFalse();

        assertThat(turn.status()).isEqualTo(TurnStatus.RUNNING);
    }

    @Test
    void completeTurnIfRunningUpdatesPersistenceBeforeMemory() {
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        ConversationService service = new ConversationService(null, persistence, null, null);
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth", 3, "user", "tenant", "platform");
        Thread thread = service.createThread("C:/business", scope);
        Turn turn = service.startTurn(thread.id(), scope);
        turn.start();
        when(persistence.completeRunningTurn(turn.id(), scope)).thenReturn(true);

        assertThat(service.completeTurnIfRunning(turn)).isTrue();

        verify(persistence).completeRunningTurn(turn.id(), scope);
        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
    }

    @Test
    void completeTurnIfRunningKeepsMemoryRunningWhenPersistenceCasLoses() {
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        ConversationService service = new ConversationService(null, persistence, null, null);
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth", 3, "user", "tenant", "platform");
        Thread thread = service.createThread("C:/business", scope);
        Turn turn = service.startTurn(thread.id(), scope);
        turn.start();
        when(persistence.completeRunningTurn(turn.id(), scope)).thenReturn(false);

        assertThat(service.completeTurnIfRunning(turn)).isFalse();

        assertThat(turn.status()).isEqualTo(TurnStatus.RUNNING);
    }

    private TurnEntity persistedTurn(String turnId, String status) {
        TurnEntity entity = new TurnEntity();
        entity.setTurnId(turnId);
        entity.setThreadId("thread-persisted");
        entity.setStatus(status);
        entity.setStartedAt(Instant.parse("2026-07-17T00:00:00Z").toString());
        entity.setCompletedAt(status.equals("COMPLETED")
                ? Instant.parse("2026-07-17T00:00:01Z").toString() : null);
        return entity;
    }
}
