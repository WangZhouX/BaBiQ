package com.wzx.babiq.server.application.action;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wzx.babiq.server.persistence.entity.ApplicationActionEntity;
import com.wzx.babiq.server.persistence.entity.ApplicationActionEventEntity;
import com.wzx.babiq.server.persistence.mapper.ApplicationActionEventMapper;
import com.wzx.babiq.server.persistence.mapper.ApplicationActionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SQLiteApplicationActionTerminalStoreTest {

    private static final Path TEST_DB = Path.of("target", "test-db",
            "application-action-store-" + UUID.randomUUID() + ".db").toAbsolutePath();
    private static final PendingApplicationAction.ConnectionContext SCOPE =
            new PendingApplicationAction.ConnectionContext(
                    "reservation", "websocket", "desktop", "session", "auth", 7,
                    "user", "tenant", "platform");

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired private SQLiteApplicationActionTerminalStore store;
    @Autowired private ApplicationActionMapper actionMapper;
    @Autowired private ApplicationActionEventMapper eventMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationActionRedactor redactor;
    @Autowired private PlatformTransactionManager transactionManager;
    private String executionId;
    private String toolCallId;
    private PendingApplicationAction.Correlation correlation;

    @BeforeEach
    void prepareReferences() {
        executionId = "execution-store-" + UUID.randomUUID();
        toolCallId = "tool-store-" + UUID.randomUUID();
        correlation = new PendingApplicationAction.Correlation("thread-store", "turn-store", toolCallId);
        jdbc.update("""
                INSERT OR IGNORE INTO bq_threads(thread_id,title,cwd,provider_id,model,sandbox_mode,approval_policy,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, "thread-store", "t", "C:/tmp", "p", "m", "workspace_write", "on_request",
                "active", Instant.now().toString(), Instant.now().toString());
        jdbc.update("""
                INSERT OR IGNORE INTO bq_turns(turn_id,thread_id,status,input_text,cwd,provider_id,model,sandbox_mode,approval_policy,started_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, "turn-store", "thread-store", "RUNNING", "x", "C:/tmp", "p", "m",
                "workspace_write", "on_request", Instant.now().toString());
        jdbc.update("""
                INSERT OR IGNORE INTO bq_tool_calls(tool_call_id,thread_id,turn_id,tool_name,args_json,status,started_at)
                VALUES(?,?,?,?,?,?,?)
                """, toolCallId, "thread-store", "turn-store", "application_action", "{}", "running",
                Instant.now().toString());
    }

    @Test
    @DisplayName("每次转换追加事件且首终态不会被晚结果覆盖")
    void recordsEveryTransitionAndKeepsFirstTerminal() {
        PendingApplicationAction requested = action(PendingApplicationAction.State.REQUESTED, null, 1);
        store.recordRegistered(requested, "case.update", 3, "sha256:fingerprint");
        PendingApplicationAction accepted = action(PendingApplicationAction.State.ACCEPTED, null, 2);
        store.recordTransition(requested, accepted, false);
        PendingApplicationAction running = action(PendingApplicationAction.State.RUNNING, null, 3);
        store.recordTransition(accepted, running, false);
        PendingApplicationAction completed = action(PendingApplicationAction.State.COMPLETED,
                JsonNodeFactory.instance.objectNode().put("previewSummary", "safe result"), 4);
        store.recordTerminal(completed, false);
        PendingApplicationAction lateFailed = action(PendingApplicationAction.State.FAILED,
                JsonNodeFactory.instance.objectNode().put("errorCode", "late_failure")
                        .put("errorSummary", "late safe error"), 5);
        store.recordTerminal(lateFailed, true);

        ApplicationActionEntity current = actionMapper.selectById(executionId);
        assertThat(current.getStatus()).isEqualTo("COMPLETED");
        assertThat(current.getResultSummaryRedacted()).isEqualTo("safe result");
        assertThat(current.getErrorCode()).isNull();
        assertThat(current.getTerminalAt()).isNotBlank();
        assertThat(store.findTerminal(executionId, correlation, SCOPE))
                .get().extracting(PendingApplicationAction::state)
                .isEqualTo(PendingApplicationAction.State.COMPLETED);

        List<ApplicationActionEventEntity> events = store.events(executionId, SCOPE);
        assertThat(events).extracting(ApplicationActionEventEntity::getEventSequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(events).extracting(ApplicationActionEventEntity::getToStatus)
                .containsExactly("REQUESTED", "ACCEPTED", "EXECUTING", "COMPLETED", "FAILED");
        assertThat(events).extracting(ApplicationActionEventEntity::getLateResult)
                .containsExactly(false, false, false, false, true);
    }

    @Test
    @DisplayName("动作查询必须精确匹配完整身份和桌面会话")
    void queriesRequireExactIdentityAndSessionScope() {
        PendingApplicationAction requested = action(PendingApplicationAction.State.REQUESTED, null, 1);
        store.recordRegistered(requested, "case.read", 1, "sha256:read");
        store.recordTerminal(action(PendingApplicationAction.State.COMPLETED, null, 2), false);

        assertThat(store.findTerminal(executionId, correlation)).isEmpty();

        PendingApplicationAction.ConnectionContext otherTenant = new PendingApplicationAction.ConnectionContext(
                "reservation", "websocket", "desktop", "session", "auth", 7,
                "user", "tenant-b", "platform");
        assertThat(store.findTerminal(executionId, correlation, otherTenant)).isEmpty();
        assertThat(store.events(executionId, otherTenant)).isEmpty();
        assertThat(store.findByScope(otherTenant, List.of("COMPLETED"))).isEmpty();
        assertThat(store.findByScope(SCOPE, List.of("COMPLETED")))
                .extracting(ApplicationActionEntity::getExecutionId)
                .contains(executionId);
    }

    @Test
    void repeatedRegistrationIsIdempotentOnlyForExactRequestIdentity() {
        PendingApplicationAction requested = action(PendingApplicationAction.State.REQUESTED, null, 1);
        store.recordRegistered(requested, "case.read", 2, "sha256:exact");

        store.recordRegistered(requested, "case.read", 2, "sha256:exact");
        assertThat(store.events(executionId, SCOPE)).hasSize(1);

        assertRegistrationConflict(requested, "case.write", 2, "sha256:exact");
        assertRegistrationConflict(requested, "case.read", 3, "sha256:exact");
        assertRegistrationConflict(requested, "case.read", 2, "sha256:different");

        ApplicationActionEntity stored = actionMapper.selectById(executionId);
        assertThat(stored.getActionId()).isEqualTo("case.read");
        assertThat(stored.getActionVersion()).isEqualTo(2);
        assertThat(stored.getRequestFingerprint()).isEqualTo("sha256:exact");
        assertThat(store.events(executionId, SCOPE)).hasSize(1);
    }

    @Test
    void terminalAndLateResultNeverRewriteRegisteredRequestIdentity() {
        PendingApplicationAction requested = action(PendingApplicationAction.State.REQUESTED, null, 1);
        store.recordRegistered(requested, "case.read", 2, "sha256:exact");
        store.recordTerminal(action(PendingApplicationAction.State.COMPLETED, null, 2), false);
        store.recordTerminal(action(PendingApplicationAction.State.FAILED, null, 3), true);

        ApplicationActionEntity stored = actionMapper.selectById(executionId);
        assertThat(stored.getActionId()).isEqualTo("case.read");
        assertThat(stored.getActionVersion()).isEqualTo(2);
        assertThat(stored.getRequestFingerprint()).isEqualTo("sha256:exact");
    }

    @Test
    void currentAndEventSummariesRedactDesktopSecretsAndStayBounded() {
        PendingApplicationAction requested = action(PendingApplicationAction.State.REQUESTED, null, 1);
        store.recordRegistered(requested, "case.read", 2, "sha256:redaction");
        PendingApplicationAction accepted = action(PendingApplicationAction.State.ACCEPTED, null, 2);
        store.recordTransition(requested, accepted, false);
        String secrets = "safe password=pw-secret secret:[abc] mobile=13800138000 "
                + "idCard=330102199001011234 bankCard=6222021234567890 "
                + "Authorization: Bearer bearer-secret " + "x".repeat(600);
        PendingApplicationAction failed = action(
                PendingApplicationAction.State.FAILED,
                JsonNodeFactory.instance.objectNode()
                        .put("previewSummary", secrets)
                        .put("errorSummary", "{\"nested\":\"" + secrets + "\"}"),
                3);

        store.recordTerminal(failed, false);

        ApplicationActionEntity current = actionMapper.selectById(executionId);
        List<String> persisted = new java.util.ArrayList<>();
        persisted.add(current.getResultSummaryRedacted());
        persisted.add(current.getErrorMessageRedacted());
        store.events(executionId, SCOPE).stream()
                .map(ApplicationActionEventEntity::getPayloadSummaryRedacted)
                .filter(java.util.Objects::nonNull)
                .forEach(persisted::add);
        assertThat(persisted).allSatisfy(value -> {
            assertThat(value).contains("[REDACTED]")
                    .doesNotContain("pw-secret", "abc", "13800138000", "330102199001011234",
                            "6222021234567890", "bearer-secret", "Bearer");
            assertThat(value.length()).isLessThanOrEqualTo(512);
        });
    }

    @Test
    void concurrentTerminalWritersKeepOneWinnerAndAppendTheLoserAsLate() throws Exception {
        PendingApplicationAction requested = action(PendingApplicationAction.State.REQUESTED, null, 1);
        store.recordRegistered(requested, "case.read", 2, "sha256:terminal-race");
        PendingApplicationAction accepted = action(PendingApplicationAction.State.ACCEPTED, null, 2);
        PendingApplicationAction running = action(PendingApplicationAction.State.RUNNING, null, 3);
        store.recordTransition(requested, accepted, false);
        store.recordTransition(accepted, running, false);
        PendingApplicationAction completed = action(PendingApplicationAction.State.COMPLETED, null, 4);
        PendingApplicationAction failed = action(PendingApplicationAction.State.FAILED, null, 5);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                store.recordTerminal(completed, false);
                return true;
            });
            var second = pool.submit(() -> {
                start.await();
                store.recordTerminal(failed, false);
                return true;
            });
            start.countDown();

            assertThat(first.get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        ApplicationActionEntity current = actionMapper.selectById(executionId);
        assertThat(current.getStatus()).isIn("COMPLETED", "FAILED");
        List<ApplicationActionEventEntity> audit = store.events(executionId, SCOPE);
        assertThat(audit).extracting(ApplicationActionEventEntity::getEventSequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(audit.subList(3, 5)).extracting(ApplicationActionEventEntity::getLateResult)
                .containsExactlyInAnyOrder(false, true);
    }

    @Test
    void concurrentLateEventsKeepStrictlyUniqueContiguousSequences() throws Exception {
        PendingApplicationAction requested = action(PendingApplicationAction.State.REQUESTED, null, 1);
        store.recordRegistered(requested, "case.read", 2, "sha256:event-race");
        store.recordTerminal(action(PendingApplicationAction.State.COMPLETED, null, 2), false);
        int writers = 7;
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(writers);
        try {
            List<java.util.concurrent.Future<Boolean>> writes = new java.util.ArrayList<>();
            for (int index = 0; index < writers; index++) {
                int second = index + 3;
                writes.add(pool.submit(() -> {
                    start.await();
                    store.recordTerminal(action(PendingApplicationAction.State.FAILED, null, second), true);
                    return true;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<Boolean> write : writes) {
                assertThat(write.get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.events(executionId, SCOPE))
                .extracting(ApplicationActionEventEntity::getEventSequence)
                .containsExactly(java.util.stream.LongStream.rangeClosed(1, writers + 2L).boxed().toArray(Long[]::new));
    }

    @Test
    void separateStoreInstancesKeepLateEventSequencesUniqueAndContiguous() throws Exception {
        PendingApplicationAction requested = action(PendingApplicationAction.State.REQUESTED, null, 1);
        store.recordRegistered(requested, "case.read", 2, "sha256:multi-instance");
        store.recordTerminal(action(PendingApplicationAction.State.COMPLETED, null, 2), false);
        SQLiteApplicationActionTerminalStore secondStore = new SQLiteApplicationActionTerminalStore(
                actionMapper, eventMapper, redactor, transactionManager);
        int writesPerStore = 20;
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<Boolean>> writes = new java.util.ArrayList<>();
            for (int index = 0; index < writesPerStore * 2; index++) {
                int second = index % 7 + 3;
                SQLiteApplicationActionTerminalStore writer = index % 2 == 0 ? store : secondStore;
                writes.add(pool.submit(() -> {
                    start.await();
                    writer.recordTerminal(action(PendingApplicationAction.State.FAILED, null, second), true);
                    return true;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<Boolean> write : writes) {
                assertThat(write.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.events(executionId, SCOPE))
                .extracting(ApplicationActionEventEntity::getEventSequence)
                .containsExactly(java.util.stream.LongStream.rangeClosed(1, writesPerStore * 2L + 2)
                        .boxed().toArray(Long[]::new));
    }

    @Test
    void outOfOrderTransitionCannotRegressPersistedCurrentState() {
        PendingApplicationAction requested = action(PendingApplicationAction.State.REQUESTED, null, 1);
        store.recordRegistered(requested, "case.read", 2, "sha256:exact");
        PendingApplicationAction accepted = action(PendingApplicationAction.State.ACCEPTED, null, 2);
        PendingApplicationAction running = action(PendingApplicationAction.State.RUNNING, null, 3);
        store.recordTransition(requested, accepted, false);
        store.recordTransition(accepted, running, false);

        assertThatThrownBy(() -> store.recordTransition(requested, accepted, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transition order");

        assertThat(actionMapper.selectById(executionId).getStatus()).isEqualTo("EXECUTING");
        assertThat(store.events(executionId, SCOPE)).extracting(ApplicationActionEventEntity::getToStatus)
                .containsExactly("REQUESTED", "ACCEPTED", "EXECUTING");
    }

    @Test
    @DisplayName("Pending 状态机在锁外把每个合法转换写入 SQLite")
    void pendingStateMachinePersistsEveryTransitionOutsideEntryLock() {
        ApplicationActionTimeoutProperties timeouts = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (PendingApplicationActions pending = new PendingApplicationActions(
                timeouts, store, ignored -> java.util.concurrent.CompletableFuture.completedFuture(
                PendingApplicationActions.RemoteStatus.running()), Executors.newSingleThreadScheduledExecutor(),
                Clock.systemUTC())) {
            pending.register(executionId, correlation, PendingApplicationAction.Path.HIGH_RISK, SCOPE);
            assertThat(pending.acceptedAuthorized(executionId, correlation, SCOPE)).isTrue();
            assertThat(pending.previewedAuthorized(executionId, correlation, SCOPE)).isTrue();
            assertThat(pending.approvalRequiredAuthorized(executionId, correlation, SCOPE)).isTrue();
            assertThat(pending.runningAuthorized(executionId, correlation, SCOPE)).isTrue();
            assertThat(pending.terminalAuthorized(executionId, correlation, SCOPE,
                    PendingApplicationAction.State.COMPLETED, null)).isTrue();
        }

        assertThat(store.events(executionId, SCOPE))
                .extracting(ApplicationActionEventEntity::getToStatus)
                .containsExactly("REQUESTED", "ACCEPTED", "PREVIEWED", "APPROVAL_REQUIRED", "EXECUTING", "COMPLETED");
    }

    @Test
    void pendingRetriesTransientFailuresUntilEveryEventIsDurableInSQLite() {
        ApplicationActionTimeoutProperties timeouts = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        AtomicInteger failures = new AtomicInteger(4);
        ApplicationActionTerminalStore flaky = new ApplicationActionTerminalStore() {
            @Override
            public void recordRegistered(
                    PendingApplicationAction requested, String actionId, int actionVersion, String fingerprint) {
                store.recordRegistered(requested, actionId, actionVersion, fingerprint);
            }

            @Override
            public Optional<PendingApplicationAction> findTerminal(
                    String executionId, PendingApplicationAction.Correlation correlation) {
                return store.findTerminal(executionId, correlation);
            }

            @Override
            public void recordTransition(
                    PendingApplicationAction previous, PendingApplicationAction current, boolean lateResult) {
                if (failures.getAndDecrement() > 0) {
                    throw new IllegalStateException("transient sqlite outage");
                }
                store.recordTransition(previous, current, lateResult);
            }

            @Override
            public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
                recordTransition(null, terminal, lateResult);
            }

            @Override
            public void queueReconciliation(PendingApplicationAction terminal) {
                store.queueReconciliation(terminal);
            }
        };
        try (PendingApplicationActions pending = new PendingApplicationActions(
                timeouts, flaky, ignored -> java.util.concurrent.CompletableFuture.completedFuture(
                PendingApplicationActions.RemoteStatus.running()), Executors.newSingleThreadScheduledExecutor(),
                Clock.systemUTC())) {
            pending.register(executionId, correlation, PendingApplicationAction.Path.READ_ONLY, SCOPE,
                    new PendingApplicationActions.RegistrationMetadata("case.read", 1, "sha256:retry"), null);
            assertThat(pending.acceptedAuthorized(executionId, correlation, SCOPE)).isTrue();
            assertThat(pending.runningAuthorized(executionId, correlation, SCOPE)).isTrue();
            assertThat(pending.terminalAuthorized(executionId, correlation, SCOPE,
                    PendingApplicationAction.State.COMPLETED, null)).isTrue();

            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(store.events(executionId, SCOPE))
                            .extracting(ApplicationActionEventEntity::getToStatus)
                            .containsExactly("REQUESTED", "ACCEPTED", "EXECUTING", "COMPLETED"));
        }
    }

    @Test
    void closeDrainsTransientFailuresIntoSQLiteBeforeReturning() {
        ApplicationActionTimeoutProperties timeouts = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(1));
        AtomicInteger failures = new AtomicInteger(2);
        ApplicationActionTerminalStore flaky = flakyStore(failures);
        PendingApplicationActions pending = new PendingApplicationActions(
                timeouts, flaky, ignored -> java.util.concurrent.CompletableFuture.completedFuture(
                PendingApplicationActions.RemoteStatus.running()), Executors.newSingleThreadScheduledExecutor(),
                Clock.systemUTC());
        pending.register(executionId, correlation, PendingApplicationAction.Path.READ_ONLY, SCOPE,
                new PendingApplicationActions.RegistrationMetadata("case.read", 1, "sha256:close"), null);
        pending.acceptedAuthorized(executionId, correlation, SCOPE);

        pending.close();

        assertThat(store.events(executionId, SCOPE))
                .extracting(ApplicationActionEventEntity::getToStatus)
                .containsExactly("REQUESTED", "ACCEPTED", "CANCELED");
    }

    @Test
    void persistedReadOnlyTerminalIsAdoptedDuringRunningReconciliation() throws Exception {
        assertPersistedTerminalAdopted(PendingApplicationAction.Path.READ_ONLY);
    }

    @Test
    void persistedHighRiskTerminalIsAdoptedDuringRunningReconciliation() throws Exception {
        assertPersistedTerminalAdopted(PendingApplicationAction.Path.HIGH_RISK);
    }

    private void assertPersistedTerminalAdopted(PendingApplicationAction.Path path) throws Exception {
        ApplicationActionTimeoutProperties fastTimeouts = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofMillis(20), Duration.ofMillis(20));
        java.util.concurrent.CompletableFuture<PendingApplicationActions.RemoteStatus> status =
                new java.util.concurrent.CompletableFuture<>();
        PendingApplicationActions pending = new PendingApplicationActions(
                fastTimeouts, store, ignored -> status, Executors.newSingleThreadScheduledExecutor(),
                Clock.systemUTC());
        try {
            java.util.concurrent.CompletableFuture<PendingApplicationAction> terminal = pending.register(
                    executionId, correlation, path, SCOPE,
                    new PendingApplicationActions.RegistrationMetadata("case.read", 1, "sha256:adopt"), null);
            assertThat(pending.acceptedAuthorized(executionId, correlation, SCOPE)).isTrue();
            if (path != PendingApplicationAction.Path.READ_ONLY) {
                assertThat(pending.previewedAuthorized(executionId, correlation, SCOPE)).isTrue();
            }
            if (path == PendingApplicationAction.Path.HIGH_RISK) {
                assertThat(pending.approvalRequiredAuthorized(executionId, correlation, SCOPE)).isTrue();
            }
            assertThat(pending.runningAuthorized(executionId, correlation, SCOPE)).isTrue();
            store.recordTerminal(new PendingApplicationAction(
                    executionId, correlation, path, PendingApplicationAction.State.COMPLETED,
                    null, "stored terminal", Instant.now(), SCOPE), false);
            status.completeExceptionally(new IllegalStateException("status unavailable"));

            assertThat(terminal.get(1, java.util.concurrent.TimeUnit.SECONDS).state())
                    .isEqualTo(PendingApplicationAction.State.COMPLETED);
        } finally {
            pending.close();
        }
    }

    private PendingApplicationAction action(
            PendingApplicationAction.State state,
            com.fasterxml.jackson.databind.JsonNode payload,
            long second) {
        return new PendingApplicationAction(
                executionId, correlation, PendingApplicationAction.Path.REVERSIBLE_WRITE,
                state, payload, state.isTerminal() ? "safe reason" : null,
                Instant.parse("2026-07-17T00:00:0" + second + "Z"), SCOPE);
    }

    private void assertRegistrationConflict(
            PendingApplicationAction requested,
            String actionId,
            int version,
            String fingerprint) {
        assertThatThrownBy(() -> store.recordRegistered(requested, actionId, version, fingerprint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request identity");
    }

    private ApplicationActionTerminalStore flakyStore(AtomicInteger failures) {
        return new ApplicationActionTerminalStore() {
            @Override
            public void recordRegistered(
                    PendingApplicationAction requested, String actionId, int actionVersion, String fingerprint) {
                store.recordRegistered(requested, actionId, actionVersion, fingerprint);
            }

            @Override
            public Optional<PendingApplicationAction> findTerminal(
                    String executionId, PendingApplicationAction.Correlation correlation) {
                return store.findTerminal(executionId, correlation);
            }

            @Override
            public void recordTransition(
                    PendingApplicationAction previous, PendingApplicationAction current, boolean lateResult) {
                if (failures.getAndDecrement() > 0) {
                    throw new IllegalStateException("transient sqlite outage");
                }
                store.recordTransition(previous, current, lateResult);
            }

            @Override
            public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
                recordTransition(null, terminal, lateResult);
            }

            @Override
            public void queueReconciliation(PendingApplicationAction terminal) {
                store.queueReconciliation(terminal);
            }
        };
    }
}
