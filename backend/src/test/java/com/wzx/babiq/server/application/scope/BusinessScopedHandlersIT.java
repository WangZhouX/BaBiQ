package com.wzx.babiq.server.application.scope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.api.dto.*;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.api.method.*;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.context.ContextStatusService;
import com.wzx.babiq.server.conversation.ConversationApplicationService;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.observability.RunRecordService;
import com.wzx.babiq.server.persistence.entity.ThreadEntity;
import com.wzx.babiq.server.persistence.entity.ToolCallEntity;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.mapper.ThreadMapper;
import com.wzx.babiq.server.persistence.mapper.ToolCallMapper;
import com.wzx.babiq.server.persistence.mapper.TurnMapper;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketSession;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@SpringBootTest
class BusinessScopedHandlersIT {

    private static final Path TEST_DB = Path.of("target", "test-db",
            "business-scoped-handlers-" + UUID.randomUUID() + ".db").toAbsolutePath();
    private static final BusinessIdentityScope SCOPE_A = BusinessIdentityScope.scoped(
            "desktop", "session-a", "auth-a", 1, "user-a", "tenant-a", "platform");
    private static final BusinessIdentityScope SCOPE_B = BusinessIdentityScope.scoped(
            "desktop", "session-b", "auth-b", 2, "user-b", "tenant-b", "platform");

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired private ConversationRepository conversations;
    @Autowired private TurnPersistenceService turns;
    @Autowired private ToolCallPersistenceService tools;
    @Autowired private ThreadMapper threadMapper;
    @Autowired private TurnMapper turnMapper;
    @Autowired private ToolCallMapper toolCallMapper;

    @Test
    @DisplayName("Thread Turn ToolCall 持久化复制冻结身份且查询精确隔离")
    void persistsImmutableScopeAndFiltersEveryLookup() {
        String suffix = UUID.randomUUID().toString();
        String threadId = "thread-scope-" + suffix;
        String turnId = "turn-scope-" + suffix;
        String toolId = "tool-scope-" + suffix;
        Instant now = Instant.now();
        conversations.createThread(threadId, "scope", "C:/scope", "p", "m",
                "workspace_write", "on_request", now, SCOPE_A);
        turns.saveTurn(TurnRecord.started(turnId, threadId, "RUNNING", "x", "C:/scope",
                "p", "m", "workspace_write", "on_request", now, SCOPE_A));
        tools.recordStarted(toolId, threadId, turnId, "application_action", "{}",
                "babiq_agent", null, null, SCOPE_A, now);

        ThreadEntity thread = threadMapper.selectById(
                threadMapper.selectList(null).stream().filter(e -> threadId.equals(e.getThreadId())).findFirst().orElseThrow().getId());
        TurnEntity turn = turnMapper.selectList(null).stream().filter(e -> turnId.equals(e.getTurnId())).findFirst().orElseThrow();
        ToolCallEntity tool = toolCallMapper.selectList(null).stream().filter(e -> toolId.equals(e.getToolCallId())).findFirst().orElseThrow();
        assertScope(thread.getDesktopInstanceId(), thread.getTenantId(), thread.getIdentityEpoch());
        assertScope(turn.getDesktopInstanceId(), turn.getTenantId(), turn.getIdentityEpoch());
        assertScope(tool.getDesktopInstanceId(), tool.getTenantId(), tool.getIdentityEpoch());
        assertThat(conversations.findThread(threadId, SCOPE_A)).isPresent();
        assertThat(conversations.findThread(threadId, SCOPE_B)).isEmpty();
        assertThat(turns.findTurn(turnId, SCOPE_A)).isPresent();
        assertThat(turns.findTurn(turnId, SCOPE_B)).isEmpty();

        String legacyThreadId = "thread-legacy-" + suffix;
        String legacyTurnId = "turn-legacy-" + suffix;
        conversations.createThread(legacyThreadId, "legacy", "C:/scope", "p", "m",
                "workspace_write", "on_request", now);
        turns.saveTurn(TurnRecord.started(legacyTurnId, legacyThreadId, "RUNNING", "x", "C:/scope",
                "p", "m", "workspace_write", "on_request", now));
        assertThat(conversations.findThread(legacyThreadId)).isPresent();
        assertThat(conversations.findThread(legacyThreadId, SCOPE_A)).isEmpty();
        assertThat(turns.findTurn(legacyTurnId)).isPresent();
        assertThat(turns.findTurn(legacyTurnId, SCOPE_A)).isEmpty();
    }

    @Test
    @DisplayName("Turn ID 不能跨身份或不可变元数据重绑定且相同写入幂等")
    void turnIdCannotOverwriteImmutableOwnerOrMetadata() {
        String suffix = UUID.randomUUID().toString();
        String threadA = "thread-turn-owner-a-" + suffix;
        String threadB = "thread-turn-owner-b-" + suffix;
        String turnId = "turn-owner-" + suffix;
        Instant startedAt = Instant.parse("2026-07-17T01:00:00Z");
        conversations.createThread(threadA, "a", "C:/a", "provider-a", "model-a",
                "workspace_write", "on_request", startedAt, SCOPE_A);
        conversations.createThread(threadB, "b", "C:/b", "provider-b", "model-b",
                "read_only", "never", startedAt, SCOPE_B);
        TurnRecord original = TurnRecord.started(turnId, threadA, "RUNNING", "input-a", "C:/a",
                "provider-a", "model-a", "workspace_write", "on_request", startedAt, SCOPE_A);
        turns.saveTurn(original);

        turns.saveTurn(original);
        assertThatThrownBy(() -> turns.saveTurn(TurnRecord.started(
                turnId, threadB, "RUNNING", "input-b", "C:/b", "provider-b", "model-b",
                "read_only", "never", startedAt.plusSeconds(1), SCOPE_B)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("turn immutable metadata conflict");

        TurnEntity stored = turns.findTurn(turnId, SCOPE_A).orElseThrow();
        assertThat(stored.getThreadId()).isEqualTo(threadA);
        assertThat(stored.getInputText()).isEqualTo("input-a");
        assertThat(stored.getProviderId()).isEqualTo("provider-a");
        assertThat(stored.getTenantId()).isEqualTo(SCOPE_A.tenantId());
        assertThat(turns.findTurn(turnId, SCOPE_B)).isEmpty();

        String legacyThread = "thread-turn-legacy-" + suffix;
        String legacyTurn = "turn-legacy-owner-" + suffix;
        conversations.createThread(legacyThread, "legacy", "C:/legacy", "p", "m",
                "workspace_write", "on_request", startedAt);
        turns.saveTurn(TurnRecord.started(legacyTurn, legacyThread, "RUNNING", "legacy", "C:/legacy",
                "p", "m", "workspace_write", "on_request", startedAt));
        assertThatThrownBy(() -> turns.saveTurn(TurnRecord.started(
                legacyTurn, threadA, "RUNNING", "input-a", "C:/a", "provider-a", "model-a",
                "workspace_write", "on_request", startedAt, SCOPE_A)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("turn immutable metadata conflict");
        assertThatThrownBy(() -> turns.saveTurn(TurnRecord.started(
                turnId, legacyThread, "RUNNING", "legacy", "C:/legacy", "p", "m",
                "workspace_write", "on_request", startedAt)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("turn immutable metadata conflict");
    }

    @Test
    @DisplayName("ToolCall ID 不能跨身份或不可变元数据重绑定且相同写入幂等")
    void toolCallIdCannotOverwriteImmutableOwnerOrMetadata() {
        String suffix = UUID.randomUUID().toString();
        String threadA = "thread-tool-owner-a-" + suffix;
        String threadB = "thread-tool-owner-b-" + suffix;
        String turnA = "turn-tool-owner-a-" + suffix;
        String turnB = "turn-tool-owner-b-" + suffix;
        String toolCallId = "tool-owner-" + suffix;
        Instant startedAt = Instant.parse("2026-07-17T01:30:00Z");
        conversations.createThread(threadA, "a", "C:/a", "p", "m",
                "workspace_write", "on_request", startedAt, SCOPE_A);
        conversations.createThread(threadB, "b", "C:/b", "p", "m",
                "workspace_write", "on_request", startedAt, SCOPE_B);
        turns.saveTurn(TurnRecord.started(turnA, threadA, "RUNNING", "a", "C:/a", "p", "m",
                "workspace_write", "on_request", startedAt, SCOPE_A));
        turns.saveTurn(TurnRecord.started(turnB, threadB, "RUNNING", "b", "C:/b", "p", "m",
                "workspace_write", "on_request", startedAt, SCOPE_B));
        tools.recordStarted(toolCallId, threadA, turnA, "application_action", "{\"a\":1}",
                "babiq_agent", null, null, SCOPE_A, startedAt);
        tools.recordFinished(toolCallId, "completed", "done", null, startedAt.plusSeconds(1));

        tools.recordStarted(toolCallId, threadA, turnA, "application_action", "{\"a\":1}",
                "babiq_agent", null, null, SCOPE_A, startedAt);
        assertThatThrownBy(() -> tools.recordStarted(
                toolCallId, threadB, turnB, "write_file", "{\"b\":2}",
                "explorer", "babiq_agent", "delegation-b", SCOPE_B, startedAt.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tool call immutable metadata conflict");

        ToolCallEntity stored = toolCallMapper.selectList(null).stream()
                .filter(entity -> toolCallId.equals(entity.getToolCallId())).findFirst().orElseThrow();
        assertThat(stored.getThreadId()).isEqualTo(threadA);
        assertThat(stored.getTurnId()).isEqualTo(turnA);
        assertThat(stored.getToolName()).isEqualTo("application_action");
        assertThat(stored.getArgsJson()).isEqualTo("{\"a\":1}");
        assertThat(stored.getStatus()).isEqualTo("completed");
        assertThat(stored.getResultPreview()).isEqualTo("done");
        assertThat(stored.getTenantId()).isEqualTo(SCOPE_A.tenantId());

        String legacyThread = "thread-tool-legacy-" + suffix;
        String legacyTurn = "turn-tool-legacy-" + suffix;
        String legacyTool = "tool-legacy-owner-" + suffix;
        conversations.createThread(legacyThread, "legacy", "C:/legacy", "p", "m",
                "workspace_write", "on_request", startedAt);
        turns.saveTurn(TurnRecord.started(legacyTurn, legacyThread, "RUNNING", "legacy", "C:/legacy",
                "p", "m", "workspace_write", "on_request", startedAt));
        tools.recordStarted(legacyTool, legacyThread, legacyTurn, "read_file", "{}", startedAt);
        assertThatThrownBy(() -> tools.recordStarted(
                legacyTool, threadA, turnA, "read_file", "{}", "babiq_agent", null, null,
                SCOPE_A, startedAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tool call immutable metadata conflict");
        assertThatThrownBy(() -> tools.recordStarted(
                toolCallId, legacyThread, legacyTurn, "application_action", "{\"a\":1}", startedAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tool call immutable metadata conflict");
    }

    @Test
    @DisplayName("real history and run services isolate A B and legacy rows")
    void realServicesIsolateBusinessAndLegacyRows() {
        String suffix = UUID.randomUUID().toString();
        String threadA = "thread-a-" + suffix;
        String turnA = "turn-a-" + suffix;
        String legacyThread = "thread-legacy-" + suffix;
        String legacyTurn = "turn-legacy-" + suffix;
        Instant now = Instant.now();
        conversations.createThread(threadA, "scope-a", "C:/scope", "p", "m",
                "workspace_write", "on_request", now, SCOPE_A);
        turns.saveTurn(TurnRecord.started(turnA, threadA, "COMPLETED", "a", "C:/scope",
                "p", "m", "workspace_write", "on_request", now, SCOPE_A));
        conversations.createThread(legacyThread, "legacy", "C:/scope", "p", "m",
                "workspace_write", "on_request", now);
        turns.saveTurn(TurnRecord.started(legacyTurn, legacyThread, "COMPLETED", "legacy", "C:/scope",
                "p", "m", "workspace_write", "on_request", now));
        ConversationApplicationService history = new ConversationApplicationService(conversations, new ConversationService());
        RunRecordService runs = applicationRunRecordService();

        assertThat(history.listThreads(null, true, 100, null, SCOPE_A).threads())
                .extracting(ThreadSummaryDto::threadId).contains(threadA).doesNotContain(legacyThread);
        assertThat(history.listThreads(null, true, 100, null, SCOPE_B).threads())
                .extracting(ThreadSummaryDto::threadId).doesNotContain(threadA, legacyThread);
        assertThat(history.loadThread(threadA, 200, null, SCOPE_A).thread().threadId()).isEqualTo(threadA);
        assertThatThrownBy(() -> history.loadThread(threadA, 200, null, SCOPE_B))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.loadThread(legacyThread, 200, null, SCOPE_A))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runs.listTurns(threadA, 20, null, SCOPE_A).turns())
                .extracting(RunTurnSummaryDto::turnId).containsExactly(turnA);
        assertThatThrownBy(() -> runs.listTurns(threadA, 20, null, SCOPE_B))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runs.getTurn(turnA, SCOPE_A).turn().turnId()).isEqualTo(turnA);
        assertThatThrownBy(() -> runs.getTurn(turnA, SCOPE_B)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runs.getTurn(legacyTurn, SCOPE_A)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("scoped history and run detail exclude polluted children while legacy reads exclude business rows")
    void scopedSecondaryReadsUseAuthorizedParentsAndLegacyReadsExcludeBusinessRows() {
        String suffix = UUID.randomUUID().toString();
        String threadA = "thread-secondary-a-" + suffix;
        String threadB = "thread-secondary-b-" + suffix;
        String turnA = "turn-secondary-a-" + suffix;
        String turnB = "turn-secondary-b-" + suffix;
        Instant now = Instant.parse("2026-07-17T03:00:00Z");
        conversations.createThread(threadA, "scope-a", "C:/a", "p", "m",
                "workspace_write", "on_request", now, SCOPE_A);
        conversations.createThread(threadB, "scope-b", "C:/b", "p", "m",
                "workspace_write", "on_request", now, SCOPE_B);
        turns.saveTurn(TurnRecord.started(turnA, threadA, "COMPLETED", "a", "C:/a", "p", "m",
                "workspace_write", "on_request", now, SCOPE_A));
        turns.saveTurn(TurnRecord.started(turnB, threadB, "FAILED", "b", "C:/b", "p", "m",
                "workspace_write", "on_request", now.plusSeconds(1), SCOPE_B));

        conversations.saveItem(ItemRecord.of("item-a-" + suffix, threadA, turnA, "assistantMessage", 1,
                "{\"id\":\"item-a\",\"type\":\"assistantMessage\"}", "completed", now));
        conversations.saveItem(ItemRecord.of("item-polluted-history-" + suffix, threadA, turnB,
                "assistantMessage", 2,
                "{\"id\":\"item-polluted-history\",\"type\":\"assistantMessage\"}",
                "completed", now.plusSeconds(2)));
        conversations.saveItem(ItemRecord.of("item-polluted-run-" + suffix, threadB, turnA,
                "assistantMessage", 1,
                "{\"id\":\"item-polluted-run\",\"type\":\"assistantMessage\"}",
                "completed", now.plusSeconds(3)));
        approvals.savePending("approval-polluted-" + suffix, threadB, turnA, "write_file", "{}", now);
        tools.recordStarted("tool-polluted-" + suffix, threadB, turnA, "write_file", "{}",
                "explorer", "babiq_agent", "delegation", SCOPE_B, now);

        ConversationApplicationService history = new ConversationApplicationService(conversations, new ConversationService());
        RunRecordService runs = applicationRunRecordService();
        var summary = history.listThreads(null, true, 100, null, SCOPE_A).threads().stream()
                .filter(thread -> threadA.equals(thread.threadId())).findFirst().orElseThrow();
        assertThat(summary.lastTurnStatus()).isEqualTo("COMPLETED");
        assertThat(summary.messageCount()).isEqualTo(1);
        assertThat(history.loadThread(threadA, 200, null, SCOPE_A).items())
                .extracting(item -> item.path("id").asText()).containsExactly("item-a");
        var detail = runs.getTurn(turnA, SCOPE_A);
        assertThat(detail.items()).extracting(item -> item.path("id").asText()).containsExactly("item-a");
        assertThat(detail.approvals()).isEmpty();
        assertThat(detail.toolCalls()).isEmpty();

        assertThat(conversations.findThread(threadA)).isEmpty();
        assertThat(conversations.listRecentThreads(null, true, 100))
                .extracting(ThreadEntity::getThreadId).doesNotContain(threadA, threadB);
        assertThat(turns.findTurn(turnA)).isEmpty();
        assertThat(turns.listTurns(threadA, 100, null)).isEmpty();
        assertThat(history.listThreads(null, true, 100, null).threads())
                .extracting(ThreadSummaryDto::threadId).doesNotContain(threadA, threadB);
        assertThatThrownBy(() -> history.loadThread(threadA, 200, null))
                .isInstanceOf(JsonRpcException.class);
        assertThatThrownBy(() -> runs.getTurn(turnA)).isInstanceOf(IllegalArgumentException.class);
    }

    @Autowired private com.wzx.babiq.server.persistence.mapper.ItemMapper itemMapper;
    @Autowired private com.wzx.babiq.server.persistence.service.ApprovalPersistenceService approvals;
    @Autowired private ContextStatusService contextStatusService;

    private RunRecordService applicationRunRecordService() {
        return new RunRecordService(turns, conversations, itemMapper, approvals, tools,
                contextStatusService, new ObjectMapper());
    }

    @Test
    @DisplayName("允许的 handlers 解析一次 scope 并把 mismatch 与不存在处理为同类响应")
    void handlersPassScopeAndDoNotMutateMismatchedTargets() {
        WebSocketSession session = mock(WebSocketSession.class);
        BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
        when(scopes.resolve(session)).thenReturn(SCOPE_B);
        var params = JsonNodeFactory.instance.objectNode().put("threadId", "thread-a");

        ConversationApplicationService history = mock(ConversationApplicationService.class);
        when(history.loadThread("thread-a", 200, null, SCOPE_B)).thenThrow(new IllegalArgumentException("not found"));
        when(history.archiveThread("thread-a", SCOPE_B)).thenThrow(new IllegalArgumentException("not found"));
        assertThatThrownBy(() -> new ThreadLoadHandler(history, scopes).handle(params, session))
                .isInstanceOf(JsonRpcException.class);
        assertThatThrownBy(() -> new ThreadArchiveHandler(history, scopes).handle(params, session))
                .isInstanceOf(JsonRpcException.class);
        verify(history).loadThread("thread-a", 200, null, SCOPE_B);
        verify(history).archiveThread("thread-a", SCOPE_B);

        RunRecordService runs = mock(RunRecordService.class);
        when(runs.listTurns("thread-a", 20, null, SCOPE_B)).thenThrow(new IllegalArgumentException("not found"));
        assertThatThrownBy(() -> new RunTurnsListHandler(runs, scopes).handle(params, session))
                .isInstanceOf(JsonRpcException.class);

        ContextStatusService context = mock(ContextStatusService.class);
        when(context.status("thread-a", SCOPE_B)).thenThrow(new IllegalArgumentException("not found"));
        assertThatThrownBy(() -> new ContextStatusHandler(context, scopes).handle(params, session))
                .isInstanceOf(JsonRpcException.class);

        ConversationService live = mock(ConversationService.class);
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        PendingApplicationActions pending = mock(PendingApplicationActions.class);
        when(live.findTurn("turn-a", SCOPE_B)).thenReturn(Optional.empty());
        var turnParams = JsonNodeFactory.instance.objectNode().put("turnId", "turn-a");
        assertThatThrownBy(() -> new TurnCancelHandler(live, persistence, pending, scopes).handle(turnParams, session))
                .isInstanceOf(JsonRpcException.class);
        verifyNoInteractions(pending);
        verify(persistence, never()).markCanceled(anyString(), anyString(), anyString(), any());

        TurnExecutor executor = mock(TurnExecutor.class);
        when(persistence.findTurn("turn-a", SCOPE_B)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new TurnInterruptHandler(executor, persistence, pending, scopes)
                .handle(turnParams, session)).isInstanceOf(JsonRpcException.class);
        verifyNoInteractions(pending);
        verify(executor, never()).interrupt(anyString());
    }

    @Test
    @DisplayName("all nine permitted handlers resolve scope once and use only scoped service overloads")
    void allPermittedHandlersUseScopedServicesForAllowedIdentity() {
        WebSocketSession session = mock(WebSocketSession.class);
        BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
        when(scopes.resolve(session)).thenReturn(SCOPE_A);
        ConversationApplicationService history = mock(ConversationApplicationService.class);
        ThreadListResult listResult = new ThreadListResult(List.of(), null);
        ThreadLoadResult loadResult = new ThreadLoadResult(
                new ThreadMetaDto("thread-a", "title", "cwd", "active"), List.of(), null, null);
        ThreadArchiveResult archiveResult = new ThreadArchiveResult(true, "thread-a", true);
        when(history.listThreads(null, false, 30, null, SCOPE_A)).thenReturn(listResult);
        when(history.loadThread("thread-a", 200, null, SCOPE_A)).thenReturn(loadResult);
        when(history.archiveThread("thread-a", SCOPE_A)).thenReturn(archiveResult);
        assertThat(new ThreadListHandler(history, scopes).handle(null, session)).isSameAs(listResult);
        var threadParams = JsonNodeFactory.instance.objectNode().put("threadId", "thread-a");
        assertThat(new ThreadLoadHandler(history, scopes).handle(threadParams, session)).isSameAs(loadResult);
        assertThat(new ThreadArchiveHandler(history, scopes).handle(threadParams, session)).isSameAs(archiveResult);

        RunRecordService runs = mock(RunRecordService.class);
        RunTurnListResult runList = new RunTurnListResult(List.of(), null);
        RunTurnDetailResult runDetail = new RunTurnDetailResult(null, List.of(), null, List.of(), List.of(), null);
        when(runs.listTurns("thread-a", 20, null, SCOPE_A)).thenReturn(runList);
        when(runs.getTurn("turn-a", SCOPE_A)).thenReturn(runDetail);
        assertThat(new RunTurnsListHandler(runs, scopes).handle(threadParams, session)).isSameAs(runList);
        var turnParams = JsonNodeFactory.instance.objectNode().put("turnId", "turn-a");
        assertThat(new RunTurnGetHandler(runs, scopes).handle(turnParams, session)).isSameAs(runDetail);

        ContextStatusService context = mock(ContextStatusService.class);
        ContextStatusResult status = new ContextStatusResult(
                "thread-a", 0, 1000, 750, "snapshot-a", 10, null, 0.01, "ok", null, 0, null);
        ContextSnapshotDto snapshot = new ContextSnapshotDto(
                "snapshot-a", "thread-a", "turn-a", "pre_model_call", "provider", "model", "cwd",
                0, 1000, 750, 10, null, 1, 0, 0.01, "preview", Instant.now().toString(), List.of());
        when(context.status("thread-a", SCOPE_A)).thenReturn(status);
        when(context.snapshot("snapshot-a", SCOPE_A)).thenReturn(Optional.of(snapshot));
        assertThat(new ContextStatusHandler(context, scopes).handle(threadParams, session)).isSameAs(status);
        var snapshotParams = JsonNodeFactory.instance.objectNode().put("snapshotId", "snapshot-a");
        assertThat(new ContextSnapshotGetHandler(context, scopes).handle(snapshotParams, session)).isSameAs(snapshot);

        ConversationService live = mock(ConversationService.class);
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        Turn turn = mock(Turn.class);
        when(live.findTurn("turn-a", SCOPE_A)).thenReturn(Optional.of(turn));
        when(turn.status()).thenReturn(com.wzx.babiq.server.conversation.TurnStatus.RUNNING);
        assertThat(new TurnCancelHandler(live, persistence, null, scopes).handle(turnParams, session))
                .isEqualTo(Map.of("ok", true));
        verify(persistence).markCanceled("turn-a", "CANCELED", "user_cancelled", SCOPE_A);

        TurnExecutor executor = mock(TurnExecutor.class);
        TurnEntity runningTurn = new TurnEntity();
        runningTurn.setStatus("RUNNING");
        when(persistence.findTurn("turn-a", SCOPE_A)).thenReturn(Optional.of(runningTurn));
        when(executor.interrupt("turn-a")).thenReturn(true);
        assertThat(new TurnInterruptHandler(executor, persistence, null, scopes).handle(turnParams, session))
                .isEqualTo(Map.of("accepted", true));
        verify(persistence).markCanceled("turn-a", "INTERRUPTED", "user_interrupted", SCOPE_A);
        verify(history, never()).listThreads(any(), anyBoolean(), anyInt(), any());
        verify(history, never()).loadThread(anyString(), anyInt(), any());
        verify(history, never()).archiveThread(anyString());
        verify(runs, never()).listTurns(anyString(), anyInt(), any());
        verify(runs, never()).getTurn(anyString());
        verify(context, never()).status(anyString());
        verify(context, never()).snapshot(anyString());
        verify(live, never()).findTurn(anyString());
        verify(persistence, never()).findTurn(anyString());
        verify(scopes, times(9)).resolve(session);
    }

    @Test
    @DisplayName("missing and cross-scope targets return the same error class without mutation")
    void missingAndCrossScopeTargetsAreIndistinguishableAndSideEffectFree() {
        WebSocketSession session = mock(WebSocketSession.class);
        BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
        when(scopes.resolve(session)).thenReturn(SCOPE_B);
        var threadParams = JsonNodeFactory.instance.objectNode().put("threadId", "thread-a");
        var turnParams = JsonNodeFactory.instance.objectNode().put("turnId", "turn-a");
        var snapshotParams = JsonNodeFactory.instance.objectNode().put("snapshotId", "snapshot-a");

        ConversationApplicationService history = mock(ConversationApplicationService.class);
        when(history.listThreads(null, false, 30, null, SCOPE_B)).thenReturn(new ThreadListResult(List.of(), null));
        assertThat(new ThreadListHandler(history, scopes).handle(null, session))
                .isEqualTo(new ThreadListResult(List.of(), null));
        when(history.loadThread("thread-a", 200, null, SCOPE_B)).thenThrow(new IllegalArgumentException("not found"));
        when(history.archiveThread("thread-a", SCOPE_B)).thenThrow(new IllegalArgumentException("not found"));
        assertInvalid(() -> new ThreadLoadHandler(history, scopes).handle(threadParams, session));
        assertInvalid(() -> new ThreadArchiveHandler(history, scopes).handle(threadParams, session));

        RunRecordService runs = mock(RunRecordService.class);
        when(runs.listTurns("thread-a", 20, null, SCOPE_B)).thenThrow(new IllegalArgumentException("not found"));
        when(runs.getTurn("turn-a", SCOPE_B)).thenThrow(new IllegalArgumentException("not found"));
        assertInvalid(() -> new RunTurnsListHandler(runs, scopes).handle(threadParams, session));
        assertInvalid(() -> new RunTurnGetHandler(runs, scopes).handle(turnParams, session));

        ContextStatusService context = mock(ContextStatusService.class);
        when(context.status("thread-a", SCOPE_B)).thenThrow(new IllegalArgumentException("not found"));
        when(context.snapshot("snapshot-a", SCOPE_B)).thenReturn(Optional.empty());
        assertInvalid(() -> new ContextStatusHandler(context, scopes).handle(threadParams, session));
        assertInvalid(() -> new ContextSnapshotGetHandler(context, scopes).handle(snapshotParams, session));

        ConversationService live = mock(ConversationService.class);
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        PendingApplicationActions pending = mock(PendingApplicationActions.class);
        when(live.findTurn("turn-a", SCOPE_B)).thenReturn(Optional.empty());
        assertInvalid(() -> new TurnCancelHandler(live, persistence, pending, scopes).handle(turnParams, session));
        verifyNoInteractions(pending);
        verify(persistence, never()).markCanceled(anyString(), anyString(), anyString(), any());

        TurnExecutor executor = mock(TurnExecutor.class);
        when(persistence.findTurn("turn-a", SCOPE_B)).thenReturn(Optional.empty());
        assertInvalid(() -> new TurnInterruptHandler(executor, persistence, pending, scopes).handle(turnParams, session));
        verifyNoInteractions(pending);
        verifyNoInteractions(executor);
        verify(history, never()).listThreads(any(), anyBoolean(), anyInt(), any());
        verify(history, never()).loadThread(anyString(), anyInt(), any());
        verify(history, never()).archiveThread(anyString());
        verify(runs, never()).listTurns(anyString(), anyInt(), any());
        verify(runs, never()).getTurn(anyString());
        verify(context, never()).status(anyString());
        verify(context, never()).snapshot(anyString());
        verify(live, never()).findTurn(anyString());
        verify(persistence, never()).findTurn(anyString());
        verify(scopes, times(9)).resolve(session);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(JsonRpcException.class,
                error -> assertThat(error.errorCode()).isEqualTo(com.wzx.babiq.server.api.error.JsonRpcErrorCode.INVALID_PARAMS));
    }

    private static void assertScope(String desktopInstanceId, String tenantId, Long identityEpoch) {
        assertThat(desktopInstanceId).isEqualTo("desktop");
        assertThat(tenantId).isEqualTo("tenant-a");
        assertThat(identityEpoch).isEqualTo(1L);
    }
}
