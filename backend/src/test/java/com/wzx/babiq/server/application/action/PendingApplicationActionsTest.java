package com.wzx.babiq.server.application.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 Agent 侧待执行桌面动作的生命周期、超时和 first-terminal-wins 语义。 */
class PendingApplicationActionsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final RecordingTerminalStore terminalStore = new RecordingTerminalStore();
    private final ApplicationActionTimeoutProperties timeouts = new ApplicationActionTimeoutProperties(
            Duration.ofMillis(30),
            Duration.ofMillis(30),
            Duration.ofMillis(30),
            Duration.ofMillis(50),
            Duration.ofMillis(20));
    private final PendingApplicationActions actions = new PendingApplicationActions(
            timeouts,
            terminalStore,
            action -> CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()),
            scheduler,
            Clock.systemUTC());

    @AfterEach
    void shutDownScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void registerCreatesRequestedSnapshotAndIncompleteTerminalFuture() {
        PendingApplicationAction.Correlation correlation = correlation("tool-1");

        CompletableFuture<PendingApplicationAction> terminal = actions.register(
                "execution-1", correlation, PendingApplicationAction.Path.HIGH_RISK);

        assertThat(terminal).isNotDone();
        assertThat(actions.snapshot("execution-1"))
                .get()
                .extracting(PendingApplicationAction::state)
                .isEqualTo(PendingApplicationAction.State.REQUESTED);
    }

    @Test
    void registrationPersistenceFailureRemovesCandidateAndFailsRegistration() {
        ApplicationActionTerminalStore store = new RecordingTerminalStore() {
            @Override
            public synchronized void recordRegistered(
                    PendingApplicationAction requested,
                    String actionId,
                    int actionVersion,
                    String requestFingerprint) {
                throw new IllegalStateException("registration store unavailable");
            }
        };
        PendingApplicationActions local = actions(store, action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()));

        assertThatThrownBy(() -> local.register(
                "execution-register-failed",
                correlation("tool-register-failed"),
                PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-register-failed"),
                new PendingApplicationActions.RegistrationMetadata("case.read", 1, "sha256:fingerprint"),
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registration store unavailable");
        assertThat(local.pendingCount()).isZero();
        assertThat(local.snapshot("execution-register-failed")).isEmpty();
    }

    @Test
    void auditPublicationDrainsTransitionsInCommittedEntryOrder() throws Exception {
        BlockingAuditStore store = new BlockingAuditStore();
        ApplicationActionTimeoutProperties stableTimeouts = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        PendingApplicationActions local = new PendingApplicationActions(
                stableTimeouts, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), scheduler, Clock.systemUTC());
        PendingApplicationAction.Correlation correlation = correlation("tool-audit-fifo");
        local.register(
                "execution-audit-fifo", correlation, PendingApplicationAction.Path.REVERSIBLE_WRITE,
                connectionContext("ws-audit-fifo"));
        store.blockAccepted.set(true);

        var pool = Executors.newFixedThreadPool(3);
        try {
            var accepted = pool.submit(() -> local.accepted("execution-audit-fifo", correlation));
            assertThat(store.acceptedEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var previewed = pool.submit(() -> local.previewed("execution-audit-fifo", correlation));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1))
                    .until(() -> local.snapshot("execution-audit-fifo")
                            .map(PendingApplicationAction::state)
                            .orElse(null) == PendingApplicationAction.State.PREVIEWED);
            var running = pool.submit(() -> local.running("execution-audit-fifo", correlation));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1))
                    .until(() -> local.snapshot("execution-audit-fifo")
                            .map(PendingApplicationAction::state)
                            .orElse(null) == PendingApplicationAction.State.RUNNING);

            store.releaseAccepted.countDown();
            assertThat(accepted.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(previewed.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(running.get(1, TimeUnit.SECONDS)).isTrue();

            assertThat(store.events).containsExactly("REQUESTED", "ACCEPTED", "PREVIEWED", "EXECUTING");
            assertThat(store.currentState).isEqualTo("EXECUTING");
        } finally {
            store.releaseAccepted.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void auditPublicationKeepsTerminalBeforeLateResultWhenTerminalStoreBlocks() throws Exception {
        BlockingAuditStore store = new BlockingAuditStore();
        PendingApplicationActions local = actions(store, action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()));
        PendingApplicationAction.Correlation correlation = correlation("tool-audit-terminal-fifo");
        CompletableFuture<PendingApplicationAction> waiter = local.register(
                "execution-audit-terminal-fifo", correlation, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-audit-terminal-fifo"));
        local.accepted("execution-audit-terminal-fifo", correlation);
        local.running("execution-audit-terminal-fifo", correlation);
        store.blockNextTerminal.set(true);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var winner = pool.submit(() -> local.terminal(
                    "execution-audit-terminal-fifo", correlation,
                    PendingApplicationAction.State.COMPLETED, null));
            assertThat(waiter.get(1, TimeUnit.SECONDS).state()).isEqualTo(PendingApplicationAction.State.COMPLETED);
            assertThat(store.terminalEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var late = pool.submit(() -> local.terminal(
                    "execution-audit-terminal-fifo", correlation,
                    PendingApplicationAction.State.FAILED, null));

            store.releaseTerminal.countDown();
            assertThat(winner.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(late.get(1, TimeUnit.SECONDS)).isFalse();
            assertThat(store.events).containsExactly(
                    "REQUESTED", "ACCEPTED", "EXECUTING", "COMPLETED", "FAILED:late");
            assertThat(store.currentState).isEqualTo("COMPLETED");
        } finally {
            store.releaseTerminal.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void terminalIndexTransferCannotLoseALateResultBetweenPendingAndCompleted() throws Exception {
        GapDetectingTerminalStore store = new GapDetectingTerminalStore();
        PendingApplicationActions local = actions(store, action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()));
        String executionId = "execution-terminal-index-handoff";
        PendingApplicationAction.Correlation correlation = correlation("tool-terminal-index-handoff");
        CompletableFuture<PendingApplicationAction> waiter = local.register(
                executionId, correlation, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-terminal-index-handoff"));
        local.accepted(executionId, correlation);
        local.running(executionId, correlation);

        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) org.springframework.test.util.ReflectionTestUtils
                .getField(local, "pending");
        BlockingRemoveMap replacement = new BlockingRemoveMap(executionId);
        replacement.putAll(current);
        org.springframework.test.util.ReflectionTestUtils.setField(local, "pending", replacement);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var winner = pool.submit(() -> local.terminal(
                    executionId, correlation, PendingApplicationAction.State.COMPLETED, null));
            assertThat(replacement.removed.await(1, TimeUnit.SECONDS)).isTrue();
            var late = pool.submit(() -> local.terminal(
                    executionId, correlation, PendingApplicationAction.State.FAILED, null));

            store.lookupEntered.await(200, TimeUnit.MILLISECONDS);
            replacement.releaseRemove.countDown();
            store.releaseLookup.countDown();

            assertThat(winner.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(late.get(1, TimeUnit.SECONDS)).isFalse();
            assertThat(waiter.get(1, TimeUnit.SECONDS).state())
                    .isEqualTo(PendingApplicationAction.State.COMPLETED);
            assertThat(store.terminals).extracting(StoredTerminal::lateResult)
                    .containsExactly(false, true);
        } finally {
            replacement.releaseRemove.countDown();
            store.releaseLookup.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void terminalThatReadAnActiveEntryBeforeRetirementUsesHistoricalAdmissionForItsLateAudit() throws Exception {
        RetirementRaceStore store = new RetirementRaceStore();
        store.releaseTerminal.countDown();
        PendingApplicationActions local = actions(store, action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()));
        String executionId = "execution-retired-late-race";
        PendingApplicationAction.Correlation correlation = correlation("tool-retired-late-race");
        local.register(executionId, correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted(executionId, correlation);
        local.running(executionId, correlation);

        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) org.springframework.test.util.ReflectionTestUtils
                .getField(local, "pending");
        Object entry = java.util.Objects.requireNonNull(pending.get(executionId));
        AtomicBoolean lateAccepted = new AtomicBoolean(true);
        Thread late = new Thread(() -> lateAccepted.set(local.terminal(
                executionId, correlation, PendingApplicationAction.State.FAILED, null)));
        try {
            synchronized (entry) {
                late.start();
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1))
                        .until(() -> late.getState() == Thread.State.BLOCKED);
                assertThat(local.terminal(
                        executionId, correlation, PendingApplicationAction.State.COMPLETED, null)).isTrue();
                store.failLate.set(true);
            }
            late.join(1_000);

            assertThat(late.isAlive()).isFalse();
            assertThat(lateAccepted).isFalse();
            assertThatThrownBy(local::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("audit backlog remains");

            store.failLate.set(false);
            local.close();
            assertThat(store.terminals).extracting(StoredTerminal::lateResult)
                    .containsExactly(false, true);
        } finally {
            store.releaseTerminal.countDown();
            if (late.isAlive()) {
                late.interrupt();
            }
        }
    }

    @Test
    void historicalLateAdmissionDoesNotLoseItsAuditWhenAnExistingEntryRetiresBeforeEnqueue() throws Exception {
        ManualScheduledExecutor retryScheduler = new ManualScheduledExecutor();
        HistoricalTerminalStore store = new HistoricalTerminalStore();
        String executionId = "execution-historical-existing-retirement";
        PendingApplicationAction.Correlation correlation = correlation("tool-historical-existing-retirement");
        store.putHistorical(executionId, correlation);
        store.blockLookup.set(true);
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()),
                retryScheduler, Clock.systemUTC(), 1);
        AtomicBoolean lateAccepted = new AtomicBoolean(true);
        Thread historicalLate = new Thread(() -> lateAccepted.set(local.terminal(
                executionId, correlation, PendingApplicationAction.State.FAILED, null)));
        try {
            historicalLate.start();
            assertThat(store.lookupEntered.await(1, TimeUnit.SECONDS)).isTrue();

            local.register(executionId, correlation, PendingApplicationAction.Path.READ_ONLY);
            local.accepted(executionId, correlation);
            local.running(executionId, correlation);
            store.fail.set(true);
            assertThat(local.terminal(
                    executionId, correlation, PendingApplicationAction.State.COMPLETED, null)).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> completed = (Map<String, Object>) org.springframework.test.util.ReflectionTestUtils
                    .getField(local, "completed");
            Object entry = java.util.Objects.requireNonNull(completed.get(executionId));
            synchronized (entry) {
                store.releaseLookup.countDown();
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1))
                        .until(() -> historicalLate.getState() == Thread.State.BLOCKED);
                store.fail.set(false);
                retryScheduler.runNextRetry();
            }

            historicalLate.join(1_000);
            assertThat(historicalLate.isAlive()).isFalse();
            assertThat(lateAccepted).isFalse();
            assertThat(store.lateExecutionIds).containsExactly(executionId);
            assertThat(local.completedRetentionCount()).isZero();
        } finally {
            store.releaseLookup.countDown();
            if (historicalLate.isAlive()) {
                historicalLate.interrupt();
            }
            retryScheduler.shutdownNow();
        }
    }

    @Test
    void historicalLateConstructionFailureDoesNotLeakAdmissionCapacity() throws Exception {
        HistoricalTerminalStore store = new HistoricalTerminalStore();
        String executionId = "execution-historical-construction-failure";
        PendingApplicationAction.Correlation correlation = correlation("tool-historical-construction-failure");
        store.putHistorical(executionId, correlation);
        OneShotThrowingClock clock = new OneShotThrowingClock();
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()),
                scheduler, clock, 1);

        assertThat(local.terminal(
                executionId, correlation, PendingApplicationAction.State.FAILED, null)).isFalse();
        assertThat(local.completedRetentionCount()).isZero();

        CompletableFuture<PendingApplicationAction> reusable = local.register(
                "execution-after-construction-failure",
                correlation("tool-after-construction-failure"),
                PendingApplicationAction.Path.READ_ONLY);
        local.close();
        assertThat(reusable.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.CANCELED);
    }

    @Test
    void concurrentHistoricalLateResultsRespectAdmissionCapacityAndReleaseAfterDrain() throws Exception {
        HistoricalTerminalStore store = new HistoricalTerminalStore();
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), scheduler, Clock.systemUTC(), 1);
        store.putHistorical("execution-history-a", correlation("tool-history-a"));
        store.putHistorical("execution-history-b", correlation("tool-history-b"));
        store.fail.set(true);

        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return local.terminal("execution-history-a", correlation("tool-history-a"),
                        PendingApplicationAction.State.FAILED, null);
            });
            var second = pool.submit(() -> {
                start.await();
                return local.terminal("execution-history-b", correlation("tool-history-b"),
                        PendingApplicationAction.State.FAILED, null);
            });
            start.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isFalse();
            assertThat(second.get(1, TimeUnit.SECONDS)).isFalse();
        } finally {
            pool.shutdownNow();
        }
        assertThat(local.completedRetentionCount()).isEqualTo(1);

        store.fail.set(false);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1))
                .until(() -> local.completedRetentionCount() == 0);
        assertThat(store.lateExecutionIds).hasSize(1);
        String rejected = store.lateExecutionIds.contains("execution-history-a")
                ? "execution-history-b" : "execution-history-a";
        String rejectedTool = rejected.endsWith("b") ? "tool-history-b" : "tool-history-a";
        assertThat(local.terminal(rejected, correlation(rejectedTool),
                PendingApplicationAction.State.FAILED, null)).isFalse();
        assertThat(store.lateExecutionIds).containsExactlyInAnyOrder(
                "execution-history-a", "execution-history-b");
    }

    @Test
    void closeWaitsForInFlightHistoricalLateAdmissionAndDrainsItBeforeSuccess() throws Exception {
        HistoricalTerminalStore store = new HistoricalTerminalStore();
        store.putHistorical("execution-close-history", correlation("tool-close-history"));
        store.blockLookup.set(true);
        store.lateFailures.set(2);
        ApplicationActionTimeoutProperties closeGrace = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(1));
        PendingApplicationActions local = new PendingApplicationActions(
                closeGrace, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), scheduler, Clock.systemUTC(), 1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var late = pool.submit(() -> local.terminal(
                    "execution-close-history", correlation("tool-close-history"),
                    PendingApplicationAction.State.FAILED, null));
            assertThat(store.lookupEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var close = pool.submit(() -> {
                local.close();
                return true;
            });
            assertThat(close.isDone()).isFalse();

            store.releaseLookup.countDown();
            assertThat(late.get(1, TimeUnit.SECONDS)).isFalse();
            assertThat(close.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(store.lateExecutionIds).containsExactly("execution-close-history");
            assertThat(local.completedRetentionCount()).isZero();
        } finally {
            store.releaseLookup.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void historicalLateAdmissionAfterSuccessfulCloseIsRejectedWithoutBacklog() {
        HistoricalTerminalStore store = new HistoricalTerminalStore();
        store.putHistorical("execution-after-close", correlation("tool-after-close"));
        PendingApplicationActions local = actions(store, action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()));
        local.close();

        assertThat(local.terminal("execution-after-close", correlation("tool-after-close"),
                PendingApplicationAction.State.FAILED, null)).isFalse();
        assertThat(store.lateExecutionIds).isEmpty();
        assertThat(local.completedRetentionCount()).isZero();
    }

    @Test
    void closeFailsLoudlyWhenInFlightHistoricalLateExceedsGraceThenCanResume() throws Exception {
        HistoricalTerminalStore store = new HistoricalTerminalStore();
        store.putHistorical("execution-close-history-timeout", correlation("tool-close-history-timeout"));
        store.blockLookup.set(true);
        ApplicationActionTimeoutProperties fastClose = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofMillis(20));
        PendingApplicationActions local = new PendingApplicationActions(
                fastClose, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), scheduler, Clock.systemUTC(), 1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var late = pool.submit(() -> local.terminal(
                    "execution-close-history-timeout", correlation("tool-close-history-timeout"),
                    PendingApplicationAction.State.FAILED, null));
            assertThat(store.lookupEntered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(local::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("late admission remains");
            store.releaseLookup.countDown();
            assertThat(late.get(1, TimeUnit.SECONDS)).isFalse();

            local.close();
            local.close();
            assertThat(store.lateExecutionIds).containsExactly("execution-close-history-timeout");
        } finally {
            store.releaseLookup.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void transientAuditFailureRetriesHeadBeforePublishingLaterTransitions() throws Exception {
        ManualScheduledExecutor retryScheduler = new ManualScheduledExecutor();
        FailingAuditStore store = new FailingAuditStore(1);
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), retryScheduler, Clock.systemUTC());
        PendingApplicationAction.Correlation correlation = correlation("tool-audit-retry");
        local.register("execution-audit-retry", correlation, PendingApplicationAction.Path.REVERSIBLE_WRITE,
                connectionContext("ws-audit-retry"));

        assertThat(local.accepted("execution-audit-retry", correlation)).isTrue();
        assertThat(local.previewed("execution-audit-retry", correlation)).isTrue();
        assertThat(local.running("execution-audit-retry", correlation)).isTrue();
        assertThat(store.events).containsExactly("REQUESTED");

        retryScheduler.runNextRetry();

        assertThat(store.events).containsExactly("REQUESTED", "ACCEPTED", "PREVIEWED", "EXECUTING");
        assertThat(store.currentState).isEqualTo("EXECUTING");
    }

    @Test
    void auditRetryContinuesPastInitialBudgetUntilDurableStoreRecovers() throws Exception {
        ManualScheduledExecutor retryScheduler = new ManualScheduledExecutor();
        FailingAuditStore store = new FailingAuditStore(4);
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), retryScheduler, Clock.systemUTC());
        PendingApplicationAction.Correlation correlation = correlation("tool-audit-permanent");
        CompletableFuture<PendingApplicationAction> waiter = local.register(
                "execution-audit-permanent", correlation, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-audit-permanent"));
        local.accepted("execution-audit-permanent", correlation);
        local.running("execution-audit-permanent", correlation);

        assertThat(local.terminal("execution-audit-permanent", correlation,
                PendingApplicationAction.State.COMPLETED, null)).isTrue();
        assertThat(waiter.get(1, TimeUnit.SECONDS).state()).isEqualTo(PendingApplicationAction.State.COMPLETED);

        retryScheduler.runAllRetries();

        assertThat(store.events).containsExactly("REQUESTED", "ACCEPTED", "EXECUTING", "COMPLETED");
        assertThat(store.reconciliationQueue).isEmpty();
        assertThat(store.recordAttempts.get()).isEqualTo(5);
    }

    @Test
    void fullAuditBacklogRejectsNewRegistrationWithoutEvictingExistingEntries() {
        ManualScheduledExecutor retryScheduler = new ManualScheduledExecutor();
        FailingAuditStore store = new FailingAuditStore(Integer.MAX_VALUE);
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), retryScheduler, Clock.systemUTC(), 2);

        for (int index = 0; index < 2; index++) {
            String executionId = "execution-bounded-" + index;
            PendingApplicationAction.Correlation correlation = correlation("tool-bounded-" + index);
            local.register(executionId, correlation, PendingApplicationAction.Path.READ_ONLY,
                    connectionContext("ws-bounded-" + index));
            local.accepted(executionId, correlation);
            local.running(executionId, correlation);
            local.terminal(executionId, correlation, PendingApplicationAction.State.COMPLETED, null);
        }

        assertThat(local.completedRetentionCount()).isEqualTo(2);
        assertThatThrownBy(() -> local.register(
                "execution-bounded-2", correlation("tool-bounded-2"), PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-bounded-2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit backlog capacity");
        assertThat(local.completedRetentionCount()).isEqualTo(2);

        assertThat(local.terminal(
                "execution-bounded-0", correlation("tool-bounded-0"),
                PendingApplicationAction.State.FAILED, null)).isFalse();
        assertThat(local.completedRetentionCount()).isEqualTo(2);
    }

    @Test
    void sameExecutionLateFloodIsBoundedAndDrainsAcceptedLateResultsInFifoOrder() {
        ManualScheduledExecutor retryScheduler = new ManualScheduledExecutor();
        SwitchableEventAuditStore store = new SwitchableEventAuditStore();
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), retryScheduler, Clock.systemUTC(), 2);
        String executionId = "execution-late-flood";
        PendingApplicationAction.Correlation correlation = correlation("tool-late-flood");
        local.register(executionId, correlation, PendingApplicationAction.Path.READ_ONLY);
        store.fail.set(true);
        local.accepted(executionId, correlation);
        local.running(executionId, correlation);
        local.terminal(executionId, correlation, PendingApplicationAction.State.COMPLETED, null);

        local.terminal(executionId, correlation, PendingApplicationAction.State.FAILED, null);
        local.terminal(executionId, correlation, PendingApplicationAction.State.CANCELED, null);
        local.terminal(executionId, correlation, PendingApplicationAction.State.REJECTED, null);

        assertThat(local.pendingLateAuditCount()).isEqualTo(2);
        store.fail.set(false);
        retryScheduler.runAllRetries();

        assertThat(store.events).containsExactly(
                "REQUESTED", "ACCEPTED", "EXECUTING", "COMPLETED", "FAILED:late", "CANCELED:late");
        assertThat(local.pendingLateAuditCount()).isZero();
        assertThat(local.completedRetentionCount()).isZero();
    }

    @Test
    void liveLateConstructionFailureDoesNotLeakPublicationAdmission() {
        ManualScheduledExecutor retryScheduler = new ManualScheduledExecutor();
        SwitchableEventAuditStore store = new SwitchableEventAuditStore();
        ArmableThrowingClock clock = new ArmableThrowingClock();
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), retryScheduler, clock, 1);
        String executionId = "execution-live-late-construction-failure";
        PendingApplicationAction.Correlation correlation = correlation("tool-live-late-construction-failure");
        local.register(executionId, correlation, PendingApplicationAction.Path.READ_ONLY);
        store.fail.set(true);
        local.accepted(executionId, correlation);
        local.running(executionId, correlation);
        local.terminal(executionId, correlation, PendingApplicationAction.State.COMPLETED, null);

        clock.throwNext();
        assertThat(local.terminal(
                executionId, correlation, PendingApplicationAction.State.FAILED, null)).isFalse();
        assertThat(local.pendingLateAuditCount()).isZero();

        assertThat(local.terminal(
                executionId, correlation, PendingApplicationAction.State.CANCELED, null)).isFalse();
        assertThat(local.pendingLateAuditCount()).isEqualTo(1);
        store.fail.set(false);
        retryScheduler.runAllRetries();

        assertThat(store.events).containsExactly(
                "REQUESTED", "ACCEPTED", "EXECUTING", "COMPLETED", "CANCELED:late");
        assertThat(local.pendingLateAuditCount()).isZero();
        assertThat(local.completedRetentionCount()).isZero();
    }

    @Test
    void fullAuditBacklogRejectsConcurrentRegistrationsWithoutPublishingEitherEntry() throws Exception {
        ManualScheduledExecutor retryScheduler = new ManualScheduledExecutor();
        FailingAuditStore store = new FailingAuditStore(Integer.MAX_VALUE);
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), retryScheduler, Clock.systemUTC(), 1);
        PendingApplicationAction.Correlation existing = correlation("tool-full-backlog");
        local.register("execution-full-backlog", existing, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-full-backlog"));
        local.accepted("execution-full-backlog", existing);
        local.running("execution-full-backlog", existing);
        local.terminal("execution-full-backlog", existing, PendingApplicationAction.State.COMPLETED, null);

        var pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> registerFailureAfter(start, local, "a"));
            var second = pool.submit(() -> registerFailureAfter(start, local, "b"));
            start.countDown();

            assertThat(List.of(first.get(1, TimeUnit.SECONDS), second.get(1, TimeUnit.SECONDS)))
                    .allMatch(message -> message.contains("audit backlog capacity"));
            assertThat(local.pendingCount()).isZero();
            assertThat(local.completedRetentionCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void admissionCapacityIsHeldFromActiveRegistrationUntilAuditQueueFullyDrains() {
        ManualScheduledExecutor retryScheduler = new ManualScheduledExecutor();
        SwitchableAuditStore store = new SwitchableAuditStore();
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), retryScheduler, Clock.systemUTC(), 1);
        PendingApplicationAction.Correlation first = correlation("tool-admission-a");
        local.register("execution-admission-a", first, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-admission-a"));

        assertCapacityRejected(local, "active");

        local.accepted("execution-admission-a", first);
        local.running("execution-admission-a", first);
        local.terminal("execution-admission-a", first, PendingApplicationAction.State.COMPLETED, null);
        assertCapacityRejected(local, "terminal-pending-audit");

        store.fail.set(false);
        retryScheduler.runAllRetries();
        CompletableFuture<PendingApplicationAction> admitted = local.register(
                "execution-admission-b", correlation("tool-admission-b"), PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-admission-b"));
        assertThat(admitted).isNotNull();
    }

    @Test
    void closeRetriesTransientAuditFailuresUntilTheWholeFifoIsDurable() {
        ManualScheduledExecutor retryScheduler = new ManualScheduledExecutor();
        FailingAuditStore store = new FailingAuditStore(3);
        ApplicationActionTimeoutProperties closeGrace = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(1));
        PendingApplicationActions local = new PendingApplicationActions(
                closeGrace, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), retryScheduler, Clock.systemUTC());
        PendingApplicationAction.Correlation correlation = correlation("tool-close-audit-retry");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-close-audit-retry", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-close-audit-retry", correlation);

        local.close();

        assertThat(terminal.join().state()).isEqualTo(PendingApplicationAction.State.CANCELED);
        assertThat(store.events).containsExactly("REQUESTED", "ACCEPTED", "CANCELED");
        assertThat(local.completedRetentionCount()).isZero();
    }

    @Test
    void closeWaitsForCurrentDrainerThenTakesOverFailedAuditRetry() throws Exception {
        BlockingThenFailingAuditStore store = new BlockingThenFailingAuditStore();
        PendingApplicationActions local = actions(store, action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()));
        PendingApplicationAction.Correlation correlation = correlation("tool-close-concurrent-drainer");
        local.register("execution-close-concurrent-drainer", correlation, PendingApplicationAction.Path.READ_ONLY);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var progress = pool.submit(() -> local.accepted("execution-close-concurrent-drainer", correlation));
            assertThat(store.entered.await(1, TimeUnit.SECONDS)).isTrue();
            var close = pool.submit(() -> {
                local.close();
                return true;
            });
            store.release.countDown();

            assertThat(progress.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(close.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(store.events).containsExactly("REQUESTED", "ACCEPTED", "CANCELED");
        } finally {
            store.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void closeFailsLoudlyWhenAuditStoreStaysUnavailablePastGraceDeadline() {
        ManualScheduledExecutor retryScheduler = new ManualScheduledExecutor();
        FailingAuditStore store = new FailingAuditStore(Integer.MAX_VALUE);
        ApplicationActionTimeoutProperties fastClose = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofMillis(20));
        PendingApplicationActions local = new PendingApplicationActions(
                fastClose, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), retryScheduler, Clock.systemUTC());
        PendingApplicationAction.Correlation correlation = correlation("tool-close-fail-loud");
        local.register("execution-close-fail-loud", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-close-fail-loud", correlation);

        assertThatThrownBy(local::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit backlog remains");
        assertThat(local.completedRetentionCount()).isEqualTo(1);
    }

    @Test
    void closeCanResumeAfterFailLoudAndBecomesIdempotentAfterRecovery() {
        ManualScheduledExecutor retryScheduler = new ManualScheduledExecutor();
        SwitchableAuditStore store = new SwitchableAuditStore();
        ApplicationActionTimeoutProperties fastClose = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofMillis(20));
        PendingApplicationActions local = new PendingApplicationActions(
                fastClose, store, action -> CompletableFuture.completedFuture(
                        PendingApplicationActions.RemoteStatus.running()), retryScheduler, Clock.systemUTC());
        PendingApplicationAction.Correlation correlation = correlation("tool-close-resume");
        local.register("execution-close-resume", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-close-resume", correlation);

        assertThatThrownBy(local::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit backlog remains");
        store.fail.set(false);

        local.close();
        local.close();

        assertThat(local.completedRetentionCount()).isZero();
        assertThat(store.terminals).singleElement()
                .extracting(stored -> stored.terminal().state())
                .isEqualTo(PendingApplicationAction.State.CANCELED);
    }

    @Test
    void readOnlyMayMoveAcceptedDirectlyToRunningAndCompleteExactlyOnce() {
        PendingApplicationAction.Correlation correlation = correlation("tool-read");
        CompletableFuture<PendingApplicationAction> terminal = actions.register(
                "execution-read", correlation, PendingApplicationAction.Path.READ_ONLY);

        assertThat(actions.accepted("execution-read", correlation)).isTrue();
        assertThat(actions.running("execution-read", correlation)).isTrue();
        assertThat(actions.terminal(
                "execution-read",
                correlation,
                PendingApplicationAction.State.COMPLETED,
                objectMapper.createObjectNode().put("output", "redacted"))).isTrue();

        assertThat(terminal.join().state()).isEqualTo(PendingApplicationAction.State.COMPLETED);
        assertThat(actions.snapshot("execution-read")).isEmpty();
        assertThat(actions.terminal(
                "execution-read", correlation, PendingApplicationAction.State.FAILED, null)).isFalse();
        assertThat(terminalStore.terminals).extracting(StoredTerminal::lateResult)
                .containsExactly(false, true);
    }

    @Test
    void duplicateExecutionIdWithDifferentCorrelationConflicts() {
        actions.register("execution-duplicate", correlation("tool-a"), PendingApplicationAction.Path.READ_ONLY);

        assertThatThrownBy(() -> actions.register(
                "execution-duplicate", correlation("tool-b"), PendingApplicationAction.Path.READ_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution-duplicate");
    }

    @Test
    void duplicateExecutionIdWithSameCorrelationAndPathButDifferentConnectionContextConflicts() {
        PendingApplicationAction.Correlation correlation = correlation("tool-duplicate-scope");
        actions.register(
                "execution-duplicate-scope",
                correlation,
                PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-owner"));

        assertThatThrownBy(() -> actions.register(
                "execution-duplicate-scope",
                correlation,
                PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-other")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution-duplicate-scope");
        assertThatThrownBy(() -> actions.register(
                "execution-duplicate-scope",
                correlation,
                PendingApplicationAction.Path.READ_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution-duplicate-scope");
    }

    @Test
    void authorizedProgressCannotTransitionADetachedEntryAfterExecutionIdReplacement() throws Exception {
        PendingApplicationAction.Correlation correlation = correlation("tool-detached-progress");
        PendingApplicationAction.ConnectionContext original = connectionContext("ws-detached-original");
        PendingApplicationAction.ConnectionContext replacement = connectionContext("ws-detached-replacement");
        String executionId = "execution-detached-progress";
        ApplicationActionTimeoutProperties stableTimeouts = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        PendingApplicationActions local = new PendingApplicationActions(
                stableTimeouts,
                terminalStore,
                action -> CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()),
                scheduler,
                Clock.systemUTC());
        local.register(executionId, correlation, PendingApplicationAction.Path.READ_ONLY, original);
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) org.springframework.test.util.ReflectionTestUtils
                .getField(local, "pending");
        Object oldEntry = java.util.Objects.requireNonNull(pending.get(executionId));
        AtomicBoolean accepted = new AtomicBoolean(true);
        Thread progress = new Thread(() -> accepted.set(
                local.acceptedAuthorized(executionId, correlation, original)));

        synchronized (oldEntry) {
            progress.start();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1))
                    .until(() -> progress.getState() == Thread.State.BLOCKED);
            assertThat(pending.remove(executionId, oldEntry)).isTrue();
            local.register(executionId, correlation, PendingApplicationAction.Path.READ_ONLY, replacement);
        }
        progress.join(1_000);

        assertThat(progress.isAlive()).isFalse();
        assertThat(accepted).isFalse();
        assertThat(local.snapshot(executionId)).get().satisfies(action -> {
            assertThat(action.state()).isEqualTo(PendingApplicationAction.State.REQUESTED);
            assertThat(action.connectionContext()).isEqualTo(replacement);
        });
    }

    @Test
    void registerRollsBackCandidateWhenSchedulingIsRejected() {
        RecordingTerminalStore store = new RecordingTerminalStore();
        ScheduledExecutorService rejectingScheduler = mock(ScheduledExecutorService.class);
        when(rejectingScheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("scheduler stopped"));
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts,
                store,
                action -> CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()),
                rejectingScheduler,
                Clock.systemUTC());

        assertThatThrownBy(() -> local.register(
                "execution-schedule-rejected",
                correlation("tool-schedule-rejected"),
                PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-schedule-rejected")))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        assertThat(local.pendingCount()).isZero();
    }

    @Test
    void registerPublishesTheEntryOnlyAfterItsInitialTimeoutIsInstalled() throws Exception {
        CountDownLatch initialScheduleEntered = new CountDownLatch(1);
        CountDownLatch releaseInitialSchedule = new CountDownLatch(1);
        AtomicInteger scheduleCalls = new AtomicInteger();
        AtomicReference<Runnable> requestedTimeout = new AtomicReference<>();
        AtomicReference<Runnable> acceptedTimeout = new AtomicReference<>();
        ScheduledFuture<?> requestedFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> acceptedFuture = mock(ScheduledFuture.class);
        ScheduledExecutorService controlledScheduler = mock(ScheduledExecutorService.class);
        when(controlledScheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    int call = scheduleCalls.incrementAndGet();
                    if (call == 1) {
                        requestedTimeout.set(invocation.getArgument(0));
                        initialScheduleEntered.countDown();
                        if (!releaseInitialSchedule.await(1, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("initial schedule was not released");
                        }
                        return requestedFuture;
                    }
                    acceptedTimeout.set(invocation.getArgument(0));
                    return acceptedFuture;
                });
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts,
                terminalStore,
                action -> CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()),
                controlledScheduler,
                Clock.systemUTC());
        String executionId = "execution-register-publication-race";
        PendingApplicationAction.Correlation correlation = correlation("tool-register-publication-race");
        AtomicReference<CompletableFuture<PendingApplicationAction>> terminalRef = new AtomicReference<>();
        AtomicReference<Throwable> registerFailure = new AtomicReference<>();
        Thread register = new Thread(() -> {
            try {
                terminalRef.set(local.register(executionId, correlation, PendingApplicationAction.Path.READ_ONLY));
            } catch (Throwable failure) {
                registerFailure.set(failure);
            }
        });
        register.start();
        assertThat(initialScheduleEntered.await(1, TimeUnit.SECONDS)).isTrue();

        AtomicBoolean accepted = new AtomicBoolean();
        Thread progress = new Thread(() -> accepted.set(local.accepted(executionId, correlation)));
        progress.start();
        progress.join(1_000);
        assertThat(progress.isAlive()).isFalse();
        assertThat(accepted).isFalse();

        releaseInitialSchedule.countDown();
        register.join(1_000);

        assertThat(registerFailure.get()).isNull();
        assertThat(register.isAlive()).isFalse();
        assertThat(local.accepted(executionId, correlation)).isTrue();
        verify(requestedFuture).cancel(false);
        verify(acceptedFuture, never()).cancel(false);
        requestedTimeout.get().run();
        assertThat(terminalRef.get()).isNotDone();
        acceptedTimeout.get().run();
        assertThat(terminalRef.get().join().state()).isEqualTo(PendingApplicationAction.State.EXPIRED);
    }

    @Test
    void progressListenerIsInstalledBeforeThePendingEntryCanReceiveDesktopProgress() throws Exception {
        CountDownLatch scheduling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScheduledExecutorService controlledScheduler = mock(ScheduledExecutorService.class);
        when(controlledScheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    scheduling.countDown();
                    release.await(1, TimeUnit.SECONDS);
                    return mock(ScheduledFuture.class);
                });
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts, terminalStore,
                action -> CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()),
                controlledScheduler, Clock.systemUTC());
        PendingApplicationAction.Correlation correlation = correlation("tool-atomic-listener");
        List<PendingApplicationAction.State> progress = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread register = new Thread(() -> local.register(
                "execution-atomic-listener", correlation, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-atomic-listener"), snapshot -> progress.add(snapshot.state())));

        register.start();
        assertThat(scheduling.await(1, TimeUnit.SECONDS)).isTrue();
        AtomicBoolean accepted = new AtomicBoolean();
        Thread desktopProgress = new Thread(() -> accepted.set(local.acceptedAuthorized(
                "execution-atomic-listener", correlation, connectionContext("ws-atomic-listener"))));
        desktopProgress.start();
        desktopProgress.join(1_000);
        assertThat(desktopProgress.isAlive()).isFalse();
        assertThat(accepted).isFalse();
        release.countDown();
        register.join(1_000);

        assertThat(local.acceptedAuthorized(
                "execution-atomic-listener", correlation,
                connectionContext("ws-atomic-listener"))).isTrue();
        assertThat(progress).containsExactly(PendingApplicationAction.State.ACCEPTED);
    }

    @Test
    void closeShutsDownOnlyTheOwnedSchedulerAndCompletesPendingActions() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<ApplicationActionTerminalStore> terminalProvider = mock(ObjectProvider.class);
        when(terminalProvider.getIfAvailable(any())).thenReturn(terminalStore);
        @SuppressWarnings("unchecked")
        ObjectProvider<PendingApplicationActions.StatusQuery> statusProvider = mock(ObjectProvider.class);
        PendingApplicationActions owned = new PendingApplicationActions(timeouts, terminalProvider, statusProvider);
        ScheduledExecutorService ownedScheduler = (ScheduledExecutorService) java.util.Objects.requireNonNull(
                org.springframework.test.util.ReflectionTestUtils.getField(owned, "scheduler"));
        PendingApplicationAction.Correlation ownedCorrelation = correlation("tool-owned-close");
        CompletableFuture<PendingApplicationAction> ownedTerminal = owned.register(
                "execution-owned-close", ownedCorrelation, PendingApplicationAction.Path.READ_ONLY);

        owned.close();

        assertThat(ownedScheduler.isShutdown()).isTrue();
        assertThat(ownedTerminal.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.CANCELED);
        assertThat(owned.pendingCount()).isZero();

        ScheduledExecutorService externalScheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            RecordingTerminalStore externalStore = new RecordingTerminalStore();
            PendingApplicationActions external = new PendingApplicationActions(
                    timeouts,
                    externalStore,
                    action -> CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()),
                    externalScheduler,
                    Clock.systemUTC());
            PendingApplicationAction.Correlation externalCorrelation = correlation("tool-external-close");
            CompletableFuture<PendingApplicationAction> externalTerminal = external.register(
                    "execution-external-close", externalCorrelation, PendingApplicationAction.Path.READ_ONLY);
            assertThat(external.accepted("execution-external-close", externalCorrelation)).isTrue();
            assertThat(external.running("execution-external-close", externalCorrelation)).isTrue();

            external.close();

            PendingApplicationAction unknown = externalTerminal.get(1, TimeUnit.SECONDS);
            assertThat(unknown.state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
            assertReconciliationEventuallyContains(externalStore, unknown);
            assertThat(external.pendingCount()).isZero();
            assertThat(externalScheduler.isShutdown()).isFalse();
        } finally {
            externalScheduler.shutdownNow();
        }
    }

    @Test
    void progressTransitionsKeepThePreviousStateAndTimeoutWhenReschedulingIsRejected() {
        assertRejectedProgressKeepsPreviousState(
                "accepted", PendingApplicationAction.Path.READ_ONLY,
                PendingApplicationAction.State.REQUESTED, 2,
                (local, executionId, correlation) -> local.accepted(executionId, correlation));
        assertRejectedProgressKeepsPreviousState(
                "previewed", PendingApplicationAction.Path.REVERSIBLE_WRITE,
                PendingApplicationAction.State.ACCEPTED, 3,
                (local, executionId, correlation) -> local.previewed(executionId, correlation));
        assertRejectedProgressKeepsPreviousState(
                "approval-required", PendingApplicationAction.Path.HIGH_RISK,
                PendingApplicationAction.State.PREVIEWED, 4,
                (local, executionId, correlation) -> local.approvalRequired(executionId, correlation));
        assertRejectedProgressKeepsPreviousState(
                "running", PendingApplicationAction.Path.HIGH_RISK,
                PendingApplicationAction.State.APPROVAL_REQUIRED, 5,
                (local, executionId, correlation) -> local.running(executionId, correlation));
    }

    @Test
    void statusFailureAndReconciliationGraceScheduleRejectionSafelyFinishOutcomeUnknown() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        AtomicInteger scheduleCalls = new AtomicInteger();
        List<Runnable> scheduledTasks = new ArrayList<>();
        ScheduledExecutorService rejectingGraceScheduler = mock(ScheduledExecutorService.class);
        when(rejectingGraceScheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    int call = scheduleCalls.incrementAndGet();
                    if (call == 4) {
                        throw new java.util.concurrent.RejectedExecutionException("grace scheduler stopped");
                    }
                    scheduledTasks.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts,
                store,
                action -> CompletableFuture.failedFuture(new IllegalStateException("status unavailable")),
                rejectingGraceScheduler,
                Clock.systemUTC());
        PendingApplicationAction.Correlation correlation = correlation("tool-grace-schedule-rejected");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-grace-schedule-rejected", correlation, PendingApplicationAction.Path.READ_ONLY);
        assertThat(local.accepted("execution-grace-schedule-rejected", correlation)).isTrue();
        assertThat(local.running("execution-grace-schedule-rejected", correlation)).isTrue();

        scheduledTasks.get(2).run();

        PendingApplicationAction unknown = terminal.get(1, TimeUnit.SECONDS);
        assertThat(unknown.state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertThat(unknown.reason()).contains("grace").contains("schedule");
        assertThat(store.terminals).extracting(stored -> stored.terminal().state())
                .containsExactly(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertReconciliationEventuallyContains(store, unknown);
        assertThat(local.snapshot("execution-grace-schedule-rejected")).isEmpty();
    }

    @Test
    void concurrentCancelAndDesktopTerminalUseFirstTerminalWins() throws Exception {
        PendingApplicationAction.Correlation correlation = correlation("tool-race");
        CompletableFuture<PendingApplicationAction> terminal = actions.register(
                "execution-race", correlation, PendingApplicationAction.Path.READ_ONLY);
        actions.accepted("execution-race", correlation);
        actions.running("execution-race", correlation);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cancel = pool.submit(() -> {
                start.await();
                return actions.cancel("execution-race", correlation, "agent canceled");
            });
            var completed = pool.submit(() -> {
                start.await();
                return actions.terminal(
                        "execution-race", correlation, PendingApplicationAction.State.COMPLETED, null);
            });
            start.countDown();

            assertThat(List.of(cancel.get(1, TimeUnit.SECONDS), completed.get(1, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                    .isIn(PendingApplicationAction.State.OUTCOME_UNKNOWN, PendingApplicationAction.State.COMPLETED);
            assertThat(terminalStore.terminals.get(0).lateResult()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void acceptTimeoutExpiresBeforeDesktopMayStartSideEffects() throws Exception {
        CompletableFuture<PendingApplicationAction> terminal = actions.register(
                "execution-timeout", correlation("tool-timeout"), PendingApplicationAction.Path.HIGH_RISK);

        PendingApplicationAction expired = terminal.get(1, TimeUnit.SECONDS);

        assertThat(expired.state()).isEqualTo(PendingApplicationAction.State.EXPIRED);
        assertThat(expired.reason()).contains("accept");
        assertThat(actions.snapshot("execution-timeout")).isEmpty();
    }

    @Test
    void reversibleWriteRequiresPreviewBeforeRunningAndMissingPreviewExpiresSafely() throws Exception {
        PendingApplicationAction.Correlation correlation = correlation("tool-preview");
        CompletableFuture<PendingApplicationAction> terminal = actions.register(
                "execution-preview", correlation, PendingApplicationAction.Path.REVERSIBLE_WRITE);

        assertThat(actions.accepted("execution-preview", correlation)).isTrue();
        assertThat(actions.running("execution-preview", correlation)).isFalse();

        PendingApplicationAction expired = terminal.get(1, TimeUnit.SECONDS);
        assertThat(expired.state()).isEqualTo(PendingApplicationAction.State.EXPIRED);
        assertThat(expired.reason()).contains("preview");
    }

    @Test
    void highRiskRequiresPreviewAndApprovalBeforeRunningAndApprovalTimeoutExpiresSafely() throws Exception {
        PendingApplicationAction.Correlation correlation = correlation("tool-approval");
        CompletableFuture<PendingApplicationAction> terminal = actions.register(
                "execution-approval", correlation, PendingApplicationAction.Path.HIGH_RISK);

        assertThat(actions.accepted("execution-approval", correlation)).isTrue();
        assertThat(actions.previewed("execution-approval", correlation)).isTrue();
        assertThat(actions.running("execution-approval", correlation)).isFalse();
        assertThat(actions.approvalRequired("execution-approval", correlation)).isTrue();

        PendingApplicationAction expired = terminal.get(1, TimeUnit.SECONDS);
        assertThat(expired.state()).isEqualTo(PendingApplicationAction.State.EXPIRED);
        assertThat(expired.reason()).contains("approval");
    }

    @Test
    void highRiskCompletePathReachesRunningAndTerminal() {
        PendingApplicationAction.Correlation correlation = correlation("tool-high-risk");
        CompletableFuture<PendingApplicationAction> terminal = actions.register(
                "execution-high-risk", correlation, PendingApplicationAction.Path.HIGH_RISK);

        assertThat(actions.accepted("execution-high-risk", correlation)).isTrue();
        assertThat(actions.previewed("execution-high-risk", correlation)).isTrue();
        assertThat(actions.approvalRequired("execution-high-risk", correlation)).isTrue();
        assertThat(actions.running("execution-high-risk", correlation)).isTrue();
        assertThat(actions.terminal(
                "execution-high-risk", correlation, PendingApplicationAction.State.COMPLETED, null)).isTrue();

        assertThat(terminal.join().state()).isEqualTo(PendingApplicationAction.State.COMPLETED);
    }

    @Test
    void fastReadOnlyCompletionSynthesizesRunningBeforeTerminal() throws Exception {
        BlockingAuditStore store = new BlockingAuditStore();
        PendingApplicationActions local = actions(store, action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()));
        PendingApplicationAction.Correlation correlation = correlation("tool-fast-read");
        PendingApplicationAction.ConnectionContext context = connectionContext("ws-fast-read");
        List<PendingApplicationAction.State> progress = new CopyOnWriteArrayList<>();
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-fast-read",
                correlation,
                PendingApplicationAction.Path.READ_ONLY,
                context,
                snapshot -> progress.add(snapshot.state()));

        assertThat(local.acceptedAuthorized("execution-fast-read", correlation, context)).isTrue();
        assertThat(local.terminalAuthorized(
                "execution-fast-read",
                correlation,
                context,
                PendingApplicationAction.State.COMPLETED,
                objectMapper.createObjectNode().put("state", "succeeded"))).isTrue();

        assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.COMPLETED);
        assertThat(progress).containsExactly(
                PendingApplicationAction.State.ACCEPTED,
                PendingApplicationAction.State.RUNNING,
                PendingApplicationAction.State.COMPLETED);
        assertThat(store.events).containsExactly("REQUESTED", "ACCEPTED", "EXECUTING", "COMPLETED");
    }

    @Test
    void terminalTransitionsFollowTheCompletePreRunAndRunningMatrix() {
        List<PendingApplicationAction.State> terminals = List.of(
                PendingApplicationAction.State.COMPLETED,
                PendingApplicationAction.State.FAILED,
                PendingApplicationAction.State.REJECTED,
                PendingApplicationAction.State.CANCELED,
                PendingApplicationAction.State.EXPIRED,
                PendingApplicationAction.State.OUTCOME_UNKNOWN);
        Map<PendingApplicationAction.Path, List<PendingApplicationAction.State>> reachable = Map.of(
                PendingApplicationAction.Path.READ_ONLY, List.of(
                        PendingApplicationAction.State.REQUESTED,
                        PendingApplicationAction.State.ACCEPTED,
                        PendingApplicationAction.State.RUNNING),
                PendingApplicationAction.Path.REVERSIBLE_WRITE, List.of(
                        PendingApplicationAction.State.REQUESTED,
                        PendingApplicationAction.State.ACCEPTED,
                        PendingApplicationAction.State.PREVIEWED,
                        PendingApplicationAction.State.RUNNING),
                PendingApplicationAction.Path.HIGH_RISK, List.of(
                        PendingApplicationAction.State.REQUESTED,
                        PendingApplicationAction.State.ACCEPTED,
                        PendingApplicationAction.State.PREVIEWED,
                        PendingApplicationAction.State.APPROVAL_REQUIRED,
                        PendingApplicationAction.State.RUNNING));

        for (var pathRow : reachable.entrySet()) {
            for (PendingApplicationAction.State from : pathRow.getValue()) {
                for (PendingApplicationAction.State terminalState : terminals) {
                    RecordingTerminalStore store = new RecordingTerminalStore();
                    ApplicationActionTimeoutProperties stableTimeouts = new ApplicationActionTimeoutProperties(
                            Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                            Duration.ofSeconds(5), Duration.ofSeconds(5));
                    PendingApplicationActions local = new PendingApplicationActions(
                            stableTimeouts,
                            store,
                            action -> CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()),
                            scheduler,
                            Clock.systemUTC());
                    String suffix = pathRow.getKey() + "-" + from + "-" + terminalState;
                    String executionId = "execution-matrix-" + suffix;
                    PendingApplicationAction.Correlation correlation = correlation("tool-matrix-" + suffix);
                    PendingApplicationAction.ConnectionContext context = connectionContext("ws-matrix-" + suffix);
                    CompletableFuture<PendingApplicationAction> future = local.register(
                            executionId, correlation, pathRow.getKey(), context);
                    moveToState(local, executionId, correlation, pathRow.getKey(), from);

                    boolean actual = local.terminalAuthorized(
                            executionId, correlation, context, terminalState, null);
                    boolean expected = terminalAllowed(pathRow.getKey(), from, terminalState);

                    assertThat(actual).as("%s -> %s", from, terminalState).isEqualTo(expected);
                    if (expected) {
                        PendingApplicationAction.State effective = from == PendingApplicationAction.State.RUNNING
                                && terminalState == PendingApplicationAction.State.EXPIRED
                                ? PendingApplicationAction.State.OUTCOME_UNKNOWN
                                : terminalState;
                        assertThat(future.join().state()).isEqualTo(effective);
                    } else {
                        assertThat(future).isNotDone();
                    }
                }
            }
        }
    }

    @Test
    void executeTimeoutQueriesStatusAndAdoptsTerminalAlreadyInStore() throws Exception {
        RecordingTerminalStore storedTerminal = new RecordingTerminalStore();
        PendingApplicationAction.Correlation correlation = correlation("tool-stored");
        AtomicInteger statusQueries = new AtomicInteger();
        PendingApplicationActions local = actions(
                storedTerminal,
                action -> {
                    statusQueries.incrementAndGet();
                    PendingApplicationAction completed = action.toTerminal(
                            PendingApplicationAction.State.COMPLETED, null, "recovered terminal", Clock.systemUTC().instant());
                    storedTerminal.recordTerminal(completed, false);
                    return CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running());
                });
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-stored", correlation, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-stored"));
        local.accepted("execution-stored", correlation);
        local.running("execution-stored", correlation);

        assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.COMPLETED);
        assertThat(statusQueries).hasValue(1);
        assertThat(storedTerminal.terminals).hasSize(1);
    }

    @Test
    void reconciliationStillRejectsAnInMemoryStoredTerminalWithADifferentConcretePath() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        PendingApplicationAction.Correlation correlation = correlation("tool-stored-path-mismatch");
        PendingApplicationAction.ConnectionContext context = connectionContext("ws-stored-path-mismatch");
        store.recordTerminal(new PendingApplicationAction(
                "execution-stored-path-mismatch", correlation,
                PendingApplicationAction.Path.HIGH_RISK, PendingApplicationAction.State.COMPLETED,
                null, "wrong path terminal", Instant.now(), context), false);
        PendingApplicationActions local = actions(store, action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.terminal(
                        PendingApplicationAction.State.FAILED)));
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-stored-path-mismatch", correlation,
                PendingApplicationAction.Path.READ_ONLY, context);
        local.accepted("execution-stored-path-mismatch", correlation);
        local.running("execution-stored-path-mismatch", correlation);

        assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.FAILED);
    }

    @Test
    void acknowledgementUncertainKeepsPendingSoLaterDesktopTerminalWins() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        CompletableFuture<PendingApplicationActions.RemoteStatus> status = new CompletableFuture<>();
        AtomicInteger queries = new AtomicInteger();
        PendingApplicationActions local = actions(store, action -> {
            queries.incrementAndGet();
            return status;
        });
        PendingApplicationAction.Correlation correlation = correlation("tool-ack-uncertain");
        PendingApplicationAction.ConnectionContext context = connectionContext("ws-ack-uncertain");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-ack-uncertain", correlation, PendingApplicationAction.Path.READ_ONLY, context);

        CompletableFuture<PendingApplicationAction> reconciliation = local.acknowledgementUncertain(
                "execution-ack-uncertain", correlation, context, "ack lost");
        assertThat(reconciliation).isSameAs(terminal);
        assertThat(local.terminalAuthorized(
                "execution-ack-uncertain", correlation, context,
                PendingApplicationAction.State.COMPLETED, null)).isTrue();

        assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.COMPLETED);
        assertThat(queries).hasValue(1);
        assertThat(store.reconciliationQueue).isEmpty();
    }

    @Test
    void confirmedRequestRejectionCleansRequestedEntryWithoutReconciliation() {
        PendingApplicationAction.Correlation correlation = correlation("tool-confirmed-request-rejection");
        PendingApplicationAction.ConnectionContext context = connectionContext("ws-confirmed-request-rejection");
        CompletableFuture<PendingApplicationAction> terminal = actions.register(
                "execution-confirmed-request-rejection",
                correlation,
                PendingApplicationAction.Path.READ_ONLY,
                context);

        CompletableFuture<PendingApplicationAction> rejected = actions.confirmedRequestRejected(
                "execution-confirmed-request-rejection", correlation, context,
                "remote_request_failed", "desktop rejected action request");

        assertThat(rejected).isSameAs(terminal);
        assertThat(terminal.join().state()).isEqualTo(PendingApplicationAction.State.REJECTED);
        assertThat(terminal.join().payload().path("errorCode").asText()).isEqualTo("remote_request_failed");
        assertThat(actions.snapshot("execution-confirmed-request-rejection")).isEmpty();
        assertThat(terminalStore.reconciliationQueue).isEmpty();
    }

    @Test
    void acknowledgementUncertainDisconnectBecomesOutcomeUnknownInsteadOfCanceled() {
        RecordingTerminalStore store = new RecordingTerminalStore();
        CompletableFuture<PendingApplicationActions.RemoteStatus> status = new CompletableFuture<>();
        PendingApplicationActions local = actions(store, action -> status);
        PendingApplicationAction.Correlation correlation = correlation("tool-ack-disconnect");
        PendingApplicationAction.ConnectionContext context = connectionContext("ws-ack-disconnect");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-ack-disconnect", correlation, PendingApplicationAction.Path.READ_ONLY, context);
        local.acknowledgementUncertain("execution-ack-disconnect", correlation, context, "ack lost");

        local.onConnectionClosed("ws-ack-disconnect", "desktop disconnected");

        PendingApplicationAction unknown = terminal.join();
        assertThat(unknown.executionId()).isEqualTo("execution-ack-disconnect");
        assertThat(unknown.state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertReconciliationEventuallyContains(store, unknown);
    }

    @Test
    void acknowledgementFailureThatReadActiveEntryBeforeDisconnectReusesOutcomeUnknown() throws Exception {
        ApplicationActionTerminalStore delayedAudit = new ApplicationActionTerminalStore() {
            @Override
            public Optional<PendingApplicationAction> findTerminal(
                    String executionId, PendingApplicationAction.Correlation correlation) {
                return Optional.empty();
            }

            @Override
            public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
                throw new IllegalStateException("audit not committed yet");
            }

            @Override
            public void queueReconciliation(PendingApplicationAction terminal) {
            }
        };
        PendingApplicationActions local = actions(delayedAudit, action -> new CompletableFuture<>());
        PendingApplicationAction.Correlation correlation = correlation("tool-ack-close-race");
        PendingApplicationAction.ConnectionContext context = connectionContext("ws-ack-close-race");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-ack-close-race", correlation, PendingApplicationAction.Path.READ_ONLY, context);
        local.accepted("execution-ack-close-race", correlation);
        local.running("execution-ack-close-race", correlation);
        Object entry = pendingEntry(local, "execution-ack-close-race");
        AtomicReference<CompletableFuture<PendingApplicationAction>> reconciliation = new AtomicReference<>();
        Thread acknowledgement = new Thread(() -> reconciliation.set(local.acknowledgementUncertain(
                "execution-ack-close-race", correlation, context, "ack lost concurrently with close")));

        synchronized (entry) {
            acknowledgement.start();
            awaitBlocked(acknowledgement);
            local.onConnectionClosed("ws-ack-close-race", "desktop disconnected");
        }
        acknowledgement.join(1_000);

        assertThat(acknowledgement.isAlive()).isFalse();
        assertThat(reconciliation.get().join()).isSameAs(terminal.join());
        assertThat(reconciliation.get().join().state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
    }

    @Test
    void negativeAcknowledgementThatReadActiveEntryBeforeDisconnectPreservesOutcomeUnknown() throws Exception {
        ApplicationActionTerminalStore delayedAudit = new ApplicationActionTerminalStore() {
            @Override
            public Optional<PendingApplicationAction> findTerminal(
                    String executionId, PendingApplicationAction.Correlation correlation) {
                return Optional.empty();
            }

            @Override
            public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
                throw new IllegalStateException("audit not committed yet");
            }

            @Override
            public void queueReconciliation(PendingApplicationAction terminal) {
            }
        };
        PendingApplicationActions local = actions(delayedAudit, action -> new CompletableFuture<>());
        PendingApplicationAction.Correlation correlation = correlation("tool-negative-ack-close-race");
        PendingApplicationAction.ConnectionContext context = connectionContext("ws-negative-ack-close-race");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-negative-ack-close-race",
                correlation,
                PendingApplicationAction.Path.READ_ONLY,
                context);
        local.accepted("execution-negative-ack-close-race", correlation);
        local.running("execution-negative-ack-close-race", correlation);
        Object entry = pendingEntry(local, "execution-negative-ack-close-race");
        AtomicReference<CompletableFuture<PendingApplicationAction>> retained = new AtomicReference<>();
        Thread acknowledgement = new Thread(() -> retained.set(local.confirmedRequestRejected(
                "execution-negative-ack-close-race", correlation, context,
                "remote_request_failed", "negative ack raced with close")));

        synchronized (entry) {
            acknowledgement.start();
            awaitBlocked(acknowledgement);
            local.onConnectionClosed("ws-negative-ack-close-race", "desktop disconnected");
        }
        acknowledgement.join(1_000);

        assertThat(acknowledgement.isAlive()).isFalse();
        assertThat(retained.get().join()).isSameAs(terminal.join());
        assertThat(retained.get().join().state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
    }

    @Test
    void acknowledgementUncertainCancelWithoutConfirmationBecomesOutcomeUnknown() {
        RecordingTerminalStore store = new RecordingTerminalStore();
        CompletableFuture<PendingApplicationActions.RemoteStatus> status = new CompletableFuture<>();
        PendingApplicationActions local = actions(store, action -> status);
        List<String> cancelRequests = new ArrayList<>();
        local.bindCancelSender(action -> {
            cancelRequests.add(action.executionId());
            return CompletableFuture.completedFuture(false);
        });
        PendingApplicationAction.Correlation correlation = correlation("tool-ack-cancel-unconfirmed");
        PendingApplicationAction.ConnectionContext context = connectionContext("ws-ack-cancel-unconfirmed");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-ack-cancel-unconfirmed", correlation, PendingApplicationAction.Path.READ_ONLY, context);
        local.acknowledgementUncertain(
                "execution-ack-cancel-unconfirmed", correlation, context, "ack lost");

        assertThat(local.cancelByTurn("turn-1")).isEqualTo(1);

        PendingApplicationAction unknown = terminal.join();
        assertThat(cancelRequests).containsExactly("execution-ack-cancel-unconfirmed");
        assertThat(unknown.executionId()).isEqualTo("execution-ack-cancel-unconfirmed");
        assertThat(unknown.state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertReconciliationEventuallyContains(store, unknown);
    }

    @Test
    void acknowledgementUncertainCancelAcceptsExplicitDesktopCanceledTerminal() {
        RecordingTerminalStore store = new RecordingTerminalStore();
        CompletableFuture<PendingApplicationActions.RemoteStatus> status = new CompletableFuture<>();
        ApplicationActionTimeoutProperties stableTimeouts = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        PendingApplicationActions local = new PendingApplicationActions(
                stableTimeouts, store, action -> status, scheduler, Clock.systemUTC());
        local.bindCancelSender(action -> CompletableFuture.completedFuture(true));
        PendingApplicationAction.Correlation correlation = correlation("tool-ack-cancel-confirmed");
        PendingApplicationAction.ConnectionContext context = connectionContext("ws-ack-cancel-confirmed");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-ack-cancel-confirmed", correlation, PendingApplicationAction.Path.READ_ONLY, context);
        local.acknowledgementUncertain(
                "execution-ack-cancel-confirmed", correlation, context, "ack lost");

        assertThat(local.cancelByTurn("turn-1")).isEqualTo(1);
        assertThat(terminal).isNotDone();
        assertThat(local.terminalAuthorized(
                "execution-ack-cancel-confirmed", correlation, context,
                PendingApplicationAction.State.CANCELED, null)).isTrue();

        assertThat(terminal.join().state()).isEqualTo(PendingApplicationAction.State.CANCELED);
        assertThat(store.reconciliationQueue).isEmpty();
    }

    @Test
    void acknowledgementUncertainConfirmedCancelTimesOutOutcomeUnknownWhenDesktopSendsNoTerminal()
            throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        CompletableFuture<PendingApplicationActions.RemoteStatus> status = new CompletableFuture<>();
        List<PendingApplicationAction.State> progress = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch progressPublished = new CountDownLatch(1);
        PendingApplicationActions local = actions(store, action -> status);
        local.bindCancelSender(action -> CompletableFuture.completedFuture(true));
        PendingApplicationAction.Correlation correlation = correlation("tool-ack-cancel-timeout");
        PendingApplicationAction.ConnectionContext context = connectionContext("ws-ack-cancel-timeout");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-ack-cancel-timeout", correlation, PendingApplicationAction.Path.READ_ONLY,
                context, snapshot -> {
                    progress.add(snapshot.state());
                    progressPublished.countDown();
                });
        local.acknowledgementUncertain(
                "execution-ack-cancel-timeout", correlation, context, "ack lost");

        assertThat(local.cancelByTurn("turn-1")).isEqualTo(1);

        PendingApplicationAction unknown = terminal.get(1, TimeUnit.SECONDS);
        assertThat(unknown.state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertThat(progressPublished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(progress).containsExactly(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertThat(store.terminals).extracting(stored -> stored.terminal().state())
                .containsExactly(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertReconciliationEventuallyContains(store, unknown);
    }

    @Test
    void acknowledgementUncertainDesktopSuccessOrFailureWinsBeforeTurnCancel() {
        for (PendingApplicationAction.State desktopTerminal : List.of(
                PendingApplicationAction.State.COMPLETED,
                PendingApplicationAction.State.FAILED)) {
            RecordingTerminalStore store = new RecordingTerminalStore();
            CompletableFuture<PendingApplicationActions.RemoteStatus> status = new CompletableFuture<>();
            PendingApplicationActions local = actions(store, action -> status);
            AtomicInteger cancelRequests = new AtomicInteger();
            local.bindCancelSender(action -> {
                cancelRequests.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            });
            String suffix = desktopTerminal.name().toLowerCase();
            PendingApplicationAction.Correlation correlation = correlation("tool-ack-terminal-" + suffix);
            PendingApplicationAction.ConnectionContext context = connectionContext("ws-ack-terminal-" + suffix);
            CompletableFuture<PendingApplicationAction> terminal = local.register(
                    "execution-ack-terminal-" + suffix,
                    correlation,
                    PendingApplicationAction.Path.READ_ONLY,
                    context);
            local.acknowledgementUncertain(
                    "execution-ack-terminal-" + suffix, correlation, context, "ack lost");

            assertThat(local.terminalAuthorized(
                    "execution-ack-terminal-" + suffix, correlation, context, desktopTerminal, null)).isTrue();
            assertThat(local.cancelByTurn("turn-1")).isZero();

            assertThat(terminal.join().state()).isEqualTo(desktopTerminal);
            assertThat(cancelRequests).hasValue(0);
        }
    }

    @Test
    void executeTimeoutNeverAdoptsAStoredTerminalFromAnotherConnectionScope() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        PendingApplicationAction.Correlation correlation = correlation("tool-stored-other-scope");
        PendingApplicationAction.ConnectionContext current = connectionContext("ws-current-scope");
        PendingApplicationAction.ConnectionContext old = connectionContext("ws-old-scope");
        store.recordTerminal(new PendingApplicationAction(
                "execution-stored-other-scope",
                correlation,
                PendingApplicationAction.Path.READ_ONLY,
                PendingApplicationAction.State.COMPLETED,
                null,
                "old identity terminal",
                Instant.now(),
                old), false);
        PendingApplicationActions local = actions(store, action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.terminal(
                        PendingApplicationAction.State.FAILED)));
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-stored-other-scope", correlation, PendingApplicationAction.Path.READ_ONLY, current);
        local.accepted("execution-stored-other-scope", correlation);
        local.running("execution-stored-other-scope", correlation);

        PendingApplicationAction resolved = terminal.get(1, TimeUnit.SECONDS);

        assertThat(resolved.state()).isEqualTo(PendingApplicationAction.State.FAILED);
        assertThat(resolved.connectionContext()).isEqualTo(current);
    }

    @Test
    void executeTimeoutWithRemoteRunningWaitsGraceThenBecomesOutcomeUnknownNeverExpired() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        AtomicInteger statusQueries = new AtomicInteger();
        PendingApplicationActions local = actions(store, action -> {
            statusQueries.incrementAndGet();
            return CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running());
        });
        PendingApplicationAction.Correlation correlation = correlation("tool-grace");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-grace", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-grace", correlation);
        local.running("execution-grace", correlation);

        PendingApplicationAction unknown = terminal.get(1, TimeUnit.SECONDS);

        assertThat(unknown.state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertThat(statusQueries).hasValue(1);
        assertReconciliationEventuallyContains(store, unknown);
    }

    @Test
    void executeStatusQueryFailureStillBecomesOutcomeUnknownNeverExpired() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        PendingApplicationActions local = actions(store, action -> CompletableFuture.failedFuture(
                new IllegalStateException("status unavailable")));
        PendingApplicationAction.Correlation correlation = correlation("tool-query-failure");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-query-failure", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-query-failure", correlation);
        local.running("execution-query-failure", correlation);

        PendingApplicationAction unknown = terminal.get(1, TimeUnit.SECONDS);

        assertThat(unknown.state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertReconciliationEventuallyContains(store, unknown);
    }

    @Test
    void blockingReconciliationDoesNotDelayTerminalFutureAndEventuallyPublishes() throws Exception {
        CountDownLatch queueStarted = new CountDownLatch(1);
        CountDownLatch releaseQueue = new CountDownLatch(1);
        RecordingTerminalStore store = new RecordingTerminalStore() {
            @Override
            public synchronized void queueReconciliation(PendingApplicationAction terminal) {
                queueStarted.countDown();
                try {
                    assertThat(releaseQueue.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                super.queueReconciliation(terminal);
            }
        };
        PendingApplicationActions local = actions(store, action -> CompletableFuture.failedFuture(
                new IllegalStateException("status unavailable")));
        PendingApplicationAction.Correlation correlation = correlation("tool-queue-order");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-queue-order", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-queue-order", correlation);
        local.running("execution-queue-order", correlation);

        assertThat(queueStarted.await(1, TimeUnit.SECONDS)).isTrue();
        PendingApplicationAction unknown = terminal.get(1, TimeUnit.SECONDS);
        assertThat(unknown.state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertThat(store.reconciliationQueue).isEmpty();
        releaseQueue.countDown();

        assertReconciliationEventuallyContains(store, unknown);
    }

    @Test
    void executeStatusQueryMayReturnAConfirmedTerminalDirectly() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        PendingApplicationActions local = actions(store, action -> CompletableFuture.completedFuture(
                PendingApplicationActions.RemoteStatus.terminal(PendingApplicationAction.State.FAILED)));
        PendingApplicationAction.Correlation correlation = correlation("tool-query-terminal");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-query-terminal", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-query-terminal", correlation);
        local.running("execution-query-terminal", correlation);

        assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.FAILED);
        assertThat(store.reconciliationQueue).isEmpty();
    }

    @Test
    void executeStatusExpiredOrUnknownBecomesOutcomeUnknownAndQueuesReconciliation() throws Exception {
        for (PendingApplicationAction.State remote : List.of(
                PendingApplicationAction.State.EXPIRED,
                PendingApplicationAction.State.OUTCOME_UNKNOWN)) {
            RecordingTerminalStore store = new RecordingTerminalStore();
            PendingApplicationActions local = actions(store, action ->
                    CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.terminal(remote)));
            String suffix = remote.name().toLowerCase();
            PendingApplicationAction.Correlation correlation = correlation("tool-query-" + suffix);
            CompletableFuture<PendingApplicationAction> terminal = local.register(
                    "execution-query-" + suffix, correlation, PendingApplicationAction.Path.READ_ONLY,
                    connectionContext("ws-query-" + suffix));
            local.accepted("execution-query-" + suffix, correlation);
            local.running("execution-query-" + suffix, correlation);

            PendingApplicationAction unknown = terminal.get(1, TimeUnit.SECONDS);

            assertThat(unknown.state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
            assertReconciliationEventuallyContains(store, unknown);
        }
    }

    @Test
    void executeStatusImpossibleRejectedTerminalIsIgnoredAndBecomesOutcomeUnknown() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        PendingApplicationActions local = actions(store, action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.terminal(
                        PendingApplicationAction.State.REJECTED)));
        PendingApplicationAction.Correlation correlation = correlation("tool-query-impossible-rejected");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-query-impossible-rejected",
                correlation,
                PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-query-impossible-rejected"));
        local.accepted("execution-query-impossible-rejected", correlation);
        local.running("execution-query-impossible-rejected", correlation);

        PendingApplicationAction unknown = terminal.get(1, TimeUnit.SECONDS);

        assertThat(unknown.state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertReconciliationEventuallyContains(store, unknown);
    }

    @Test
    void terminalArrivingDuringRunningReconciliationGraceWinsOverOutcomeUnknown() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        AtomicReference<PendingApplicationActions> owner = new AtomicReference<>();
        PendingApplicationAction.Correlation correlation = correlation("tool-grace-terminal");
        PendingApplicationActions local = actions(store, action -> {
            scheduler.schedule(() -> owner.get().terminal(
                    action.executionId(),
                    action.correlation(),
                    PendingApplicationAction.State.COMPLETED,
                    null), 5, TimeUnit.MILLISECONDS);
            return CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running());
        });
        owner.set(local);
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-grace-terminal", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-grace-terminal", correlation);
        local.running("execution-grace-terminal", correlation);

        assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.COMPLETED);
        assertThat(store.reconciliationQueue).isEmpty();
    }

    @Test
    void storedTerminalAdoptionAlsoNotifiesTheProgressListener() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        List<PendingApplicationAction.State> progress = new java.util.concurrent.CopyOnWriteArrayList<>();
        PendingApplicationActions local = actions(store, action -> {
            PendingApplicationAction stored = action.toTerminal(
                    PendingApplicationAction.State.COMPLETED, null, "stored terminal", Instant.now());
            store.recordTerminal(stored, false);
            return CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running());
        });
        PendingApplicationAction.Correlation correlation = correlation("tool-stored-progress");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-stored-progress", correlation, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-stored-progress"), snapshot -> progress.add(snapshot.state()));
        local.accepted("execution-stored-progress", correlation);
        local.running("execution-stored-progress", correlation);

        assertThat(terminal.get(1, TimeUnit.SECONDS).state()).isEqualTo(PendingApplicationAction.State.COMPLETED);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(progress).containsSequence(
                        PendingApplicationAction.State.ACCEPTED,
                        PendingApplicationAction.State.RUNNING,
                        PendingApplicationAction.State.COMPLETED));
    }

    @Test
    void lateTerminalAfterFirstTerminalIsRecordedOnlyAsLateAndDoesNotReplaceConsumedResult() {
        RecordingTerminalStore store = new RecordingTerminalStore();
        PendingApplicationActions local = actions(store, action -> CompletableFuture.completedFuture(
                PendingApplicationActions.RemoteStatus.running()));
        PendingApplicationAction.Correlation correlation = correlation("tool-late");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-late", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-late", correlation);
        local.running("execution-late", correlation);
        local.terminal("execution-late", correlation, PendingApplicationAction.State.COMPLETED, null);

        assertThat(local.terminal(
                "execution-late", correlation, PendingApplicationAction.State.FAILED, null)).isFalse();

        assertThat(terminal.join().state()).isEqualTo(PendingApplicationAction.State.COMPLETED);
        assertThat(store.terminals).extracting(StoredTerminal::lateResult)
                .containsExactly(false, true);
        assertThat(store.terminals.get(1).terminal().state()).isEqualTo(PendingApplicationAction.State.FAILED);
    }

    @Test
    void terminalStoreFailureCannotStrandTheFirstTerminalWaiter() throws Exception {
        ApplicationActionTerminalStore store = throwingStore(true, false, false);
        List<PendingApplicationAction.State> progress = new java.util.concurrent.CopyOnWriteArrayList<>();
        PendingApplicationActions local = actions(store, action -> CompletableFuture.completedFuture(
                PendingApplicationActions.RemoteStatus.running()));
        PendingApplicationAction.Correlation correlation = correlation("tool-store-failure");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-store-failure", correlation, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-store-failure"), snapshot -> progress.add(snapshot.state()));
        local.accepted("execution-store-failure", correlation);
        local.running("execution-store-failure", correlation);

        assertThat(local.terminal(
                "execution-store-failure", correlation,
                PendingApplicationAction.State.COMPLETED, null)).isTrue();

        assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.COMPLETED);
        assertThat(local.pendingCount()).isZero();
        assertThat(progress).endsWith(PendingApplicationAction.State.COMPLETED);
    }

    @Test
    void lateTerminalAuditFailureDoesNotEscapeOrReplaceTheFirstTerminal() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        AtomicBoolean throwLate = new AtomicBoolean(false);
        ApplicationActionTerminalStore failingLateStore = new ApplicationActionTerminalStore() {
            @Override
            public Optional<PendingApplicationAction> findTerminal(
                    String executionId,
                    PendingApplicationAction.Correlation correlation) {
                return store.findTerminal(executionId, correlation);
            }

            @Override
            public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
                if (lateResult && throwLate.get()) {
                    throw new IllegalStateException("late audit unavailable");
                }
                store.recordTerminal(terminal, lateResult);
            }

            @Override
            public void queueReconciliation(PendingApplicationAction terminal) {
                store.queueReconciliation(terminal);
            }
        };
        PendingApplicationActions local = actions(failingLateStore, action -> CompletableFuture.completedFuture(
                PendingApplicationActions.RemoteStatus.running()));
        PendingApplicationAction.Correlation correlation = correlation("tool-late-store-failure");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-late-store-failure", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-late-store-failure", correlation);
        local.running("execution-late-store-failure", correlation);
        assertThat(local.terminal(
                "execution-late-store-failure", correlation,
                PendingApplicationAction.State.COMPLETED, null)).isTrue();
        throwLate.set(true);

        assertThat(local.terminal(
                "execution-late-store-failure", correlation,
                PendingApplicationAction.State.FAILED, null)).isFalse();
        assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.COMPLETED);
    }

    @Test
    void reconciliationQueueFailureStillCompletesOutcomeUnknown() throws Exception {
        ApplicationActionTerminalStore store = throwingStore(false, true, false);
        PendingApplicationActions local = actions(store, action -> CompletableFuture.completedFuture(
                PendingApplicationActions.RemoteStatus.running()));
        PendingApplicationAction.Correlation correlation = correlation("tool-queue-failure");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-queue-failure", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-queue-failure", correlation);
        local.running("execution-queue-failure", correlation);

        assertThat(local.cancel("execution-queue-failure", correlation, "cancel uncertain")).isTrue();

        assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertThat(local.pendingCount()).isZero();
    }

    @Test
    void reconciliationLookupFailureFallsBackToBoundedOutcomeUnknown() throws Exception {
        ApplicationActionTimeoutProperties shortReconciliation = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofMillis(10));
        PendingApplicationActions local = new PendingApplicationActions(
                shortReconciliation,
                throwingStore(false, false, true),
                action -> CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()),
                scheduler,
                Clock.systemUTC());
        PendingApplicationAction.Correlation correlation = correlation("tool-find-failure");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-find-failure", correlation, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-find-failure"));
        local.accepted("execution-find-failure", correlation);
        local.running("execution-find-failure", correlation);

        local.acknowledgementUncertain(
                "execution-find-failure", correlation,
                connectionContext("ws-find-failure"), "ack uncertain");

        assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertThat(local.pendingCount()).isZero();
    }

    @Test
    void closeCompletesEveryPendingEntryAndFailsLoudlyWhenTerminalAuditFails() throws Exception {
        ApplicationActionTimeoutProperties fastClose = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofMillis(20));
        PendingApplicationActions local = new PendingApplicationActions(
                fastClose, throwingStore(true, true, false), action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()),
                scheduler, Clock.systemUTC());
        PendingApplicationAction.Correlation firstCorrelation = correlation("tool-close-failure-1");
        PendingApplicationAction.Correlation secondCorrelation = correlation("tool-close-failure-2");
        CompletableFuture<PendingApplicationAction> first = local.register(
                "execution-close-failure-1", firstCorrelation, PendingApplicationAction.Path.READ_ONLY);
        CompletableFuture<PendingApplicationAction> second = local.register(
                "execution-close-failure-2", secondCorrelation, PendingApplicationAction.Path.READ_ONLY);

        assertThatThrownBy(local::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit backlog remains");

        assertThat(first.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.CANCELED);
        assertThat(second.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.CANCELED);
        assertThat(local.pendingCount()).isZero();
    }

    @Test
    void reentrantAcceptedListenerCannotOverwriteTheRunningTimeout() throws Exception {
        ApplicationActionTimeoutProperties shortExecution = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofMillis(15), Duration.ofMillis(10));
        PendingApplicationActions local = new PendingApplicationActions(
                shortExecution,
                new RecordingTerminalStore(),
                action -> CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()),
                scheduler,
                Clock.systemUTC());
        PendingApplicationAction.Correlation correlation = correlation("tool-reentrant-listener");
        AtomicReference<PendingApplicationActions> owner = new AtomicReference<>(local);
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-reentrant-listener", correlation, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-reentrant-listener"), snapshot -> {
                    if (snapshot.state() == PendingApplicationAction.State.ACCEPTED) {
                        owner.get().running("execution-reentrant-listener", correlation);
                    }
                });

        assertThat(local.accepted("execution-reentrant-listener", correlation)).isTrue();

        assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                .isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertThat(local.pendingCount()).isZero();
    }

    @Test
    void terminalProgressListenerRunsAfterTheEntryMonitorIsReleased() throws Exception {
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        PendingApplicationActions local = actions(new RecordingTerminalStore(), action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()));
        PendingApplicationAction.Correlation correlation = correlation("tool-terminal-listener-lock");
        local.register(
                "execution-terminal-listener-lock", correlation, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-terminal-listener-lock"), snapshot -> {
                    if (snapshot.state() == PendingApplicationAction.State.COMPLETED) {
                        listenerEntered.countDown();
                        try {
                            releaseListener.await(1, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        local.accepted("execution-terminal-listener-lock", correlation);
        local.running("execution-terminal-listener-lock", correlation);
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) org.springframework.test.util.ReflectionTestUtils
                .getField(local, "pending");
        Object entry = pending.get("execution-terminal-listener-lock");
        Thread terminalThread = new Thread(() -> local.terminal(
                "execution-terminal-listener-lock", correlation,
                PendingApplicationAction.State.COMPLETED, null));
        terminalThread.start();
        assertThat(listenerEntered.await(1, TimeUnit.SECONDS)).isTrue();
        CountDownLatch acquired = new CountDownLatch(1);
        Thread contender = new Thread(() -> {
            synchronized (entry) {
                acquired.countDown();
            }
        });
        contender.start();
        assertThat(acquired.await(200, TimeUnit.MILLISECONDS)).isTrue();
        releaseListener.countDown();
        terminalThread.join(1_000);
        contender.join(1_000);

        assertThat(acquired.getCount()).isZero();
    }

    @Test
    void concurrentDesktopTerminalsUseFirstTerminalAndRecordLoserAsLate() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        PendingApplicationActions local = actions(store, action -> CompletableFuture.completedFuture(
                PendingApplicationActions.RemoteStatus.running()));
        PendingApplicationAction.Correlation correlation = correlation("tool-terminal-race");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-terminal-race", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-terminal-race", correlation);
        local.running("execution-terminal-race", correlation);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var completed = pool.submit(() -> {
                start.await();
                return local.terminal(
                        "execution-terminal-race", correlation, PendingApplicationAction.State.COMPLETED, null);
            });
            var failed = pool.submit(() -> {
                start.await();
                return local.terminal(
                        "execution-terminal-race", correlation, PendingApplicationAction.State.FAILED, null);
            });
            start.countDown();

            assertThat(List.of(completed.get(1, TimeUnit.SECONDS), failed.get(1, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(terminal.get(1, TimeUnit.SECONDS).state())
                    .isIn(PendingApplicationAction.State.COMPLETED, PendingApplicationAction.State.FAILED);
            assertThat(store.terminals).extracting(StoredTerminal::lateResult)
                    .containsExactly(false, true);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void terminalThatReadEntryBeforeWinnerFinishedIsRecordedAsLateAfterLockRelease() throws Exception {
        RecordingTerminalStore store = new RecordingTerminalStore();
        ApplicationActionTimeoutProperties stableTimeouts = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        PendingApplicationActions local = new PendingApplicationActions(
                stableTimeouts,
                store,
                action -> CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()),
                scheduler,
                Clock.systemUTC());
        PendingApplicationAction.Correlation correlation = correlation("tool-stale-entry-terminal");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-stale-entry-terminal", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-stale-entry-terminal", correlation);
        local.running("execution-stale-entry-terminal", correlation);
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) org.springframework.test.util.ReflectionTestUtils
                .getField(local, "pending");
        Object entry = pending.get("execution-stale-entry-terminal");
        AtomicBoolean loserAccepted = new AtomicBoolean(true);
        Thread loser = new Thread(() -> loserAccepted.set(local.terminal(
                "execution-stale-entry-terminal", correlation, PendingApplicationAction.State.FAILED, null)));

        synchronized (entry) {
            loser.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (loser.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(loser.getState()).isEqualTo(Thread.State.BLOCKED);
            assertThat(local.terminal(
                    "execution-stale-entry-terminal", correlation,
                    PendingApplicationAction.State.COMPLETED, null)).isTrue();
        }
        loser.join(1_000);

        assertThat(loser.isAlive()).isFalse();
        assertThat(loserAccepted).isFalse();
        assertThat(terminal.join().state()).isEqualTo(PendingApplicationAction.State.COMPLETED);
        assertThat(store.terminals).extracting(StoredTerminal::lateResult)
                .containsExactly(false, true);
        assertThat(store.terminals.get(1).terminal().state()).isEqualTo(PendingApplicationAction.State.FAILED);
    }

    @Test
    void desktopExpiredAfterRunningBecomesOutcomeUnknownAndQueuesReconciliation() {
        RecordingTerminalStore store = new RecordingTerminalStore();
        PendingApplicationActions local = actions(store, action -> CompletableFuture.completedFuture(
                PendingApplicationActions.RemoteStatus.running()));
        PendingApplicationAction.Correlation correlation = correlation("tool-running-expired");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-running-expired", correlation, PendingApplicationAction.Path.READ_ONLY);
        local.accepted("execution-running-expired", correlation);
        local.running("execution-running-expired", correlation);

        assertThat(local.terminal(
                "execution-running-expired", correlation, PendingApplicationAction.State.EXPIRED, null)).isTrue();

        PendingApplicationAction unknown = terminal.join();
        assertThat(unknown.state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertReconciliationEventuallyContains(store, unknown);
        assertThat(store.terminals).extracting(stored -> stored.terminal().state())
                .containsExactly(PendingApplicationAction.State.OUTCOME_UNKNOWN);
    }

    @Test
    void connectionCloseTurnsRunningIntoOutcomeUnknownAndQueuesReconciliation() {
        PendingApplicationAction.Correlation correlation = correlation("tool-disconnect");
        CompletableFuture<PendingApplicationAction> terminal = actions.register(
                "execution-disconnect", correlation, PendingApplicationAction.Path.READ_ONLY);
        actions.accepted("execution-disconnect", correlation);
        actions.running("execution-disconnect", correlation);

        actions.onConnectionClosed("desktop disconnected");

        PendingApplicationAction unknown = terminal.join();
        assertThat(unknown.state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertReconciliationEventuallyContains(terminalStore, unknown);
        assertThat(terminalStore.terminals).hasSize(1);
    }

    @Test
    void connectionCloseOnlyAffectsActionsBoundToThatWebSocket() {
        PendingApplicationAction.Correlation firstCorrelation = correlation("tool-close-ws-a");
        PendingApplicationAction.Correlation secondCorrelation = correlation("tool-close-ws-b");
        CompletableFuture<PendingApplicationAction> first = actions.register(
                "execution-close-ws-a", firstCorrelation, PendingApplicationAction.Path.READ_ONLY);
        CompletableFuture<PendingApplicationAction> second = actions.register(
                "execution-close-ws-b", secondCorrelation, PendingApplicationAction.Path.READ_ONLY);
        actions.attachConnectionContext("execution-close-ws-a", firstCorrelation, connectionContext("ws-a"));
        actions.attachConnectionContext("execution-close-ws-b", secondCorrelation, connectionContext("ws-b"));
        actions.accepted("execution-close-ws-a", firstCorrelation);
        actions.running("execution-close-ws-a", firstCorrelation);
        actions.accepted("execution-close-ws-b", secondCorrelation);
        actions.running("execution-close-ws-b", secondCorrelation);

        actions.onConnectionClosed("ws-a", "desktop disconnected");

        assertThat(first.join().state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertThat(second).isNotDone();
        assertThat(actions.snapshot("execution-close-ws-b")).get()
                .extracting(PendingApplicationAction::state)
                .isEqualTo(PendingApplicationAction.State.RUNNING);
    }

    @Test
    void connectionCloseCancelsEveryPreExecutionStateAsDisconnectedBeforeExecute() {
        PendingApplicationAction.Correlation requestedCorrelation = correlation("tool-close-requested");
        PendingApplicationAction.Correlation acceptedCorrelation = correlation("tool-close-accepted");
        PendingApplicationAction.Correlation previewedCorrelation = correlation("tool-close-previewed");
        PendingApplicationAction.Correlation approvalCorrelation = correlation("tool-close-approval");
        var requested = actions.register(
                "execution-close-requested", requestedCorrelation, PendingApplicationAction.Path.HIGH_RISK);
        var accepted = actions.register(
                "execution-close-accepted", acceptedCorrelation, PendingApplicationAction.Path.HIGH_RISK);
        var previewed = actions.register(
                "execution-close-previewed", previewedCorrelation, PendingApplicationAction.Path.HIGH_RISK);
        var approval = actions.register(
                "execution-close-approval", approvalCorrelation, PendingApplicationAction.Path.HIGH_RISK);
        actions.accepted("execution-close-accepted", acceptedCorrelation);
        actions.accepted("execution-close-previewed", previewedCorrelation);
        actions.previewed("execution-close-previewed", previewedCorrelation);
        actions.accepted("execution-close-approval", approvalCorrelation);
        actions.previewed("execution-close-approval", approvalCorrelation);
        actions.approvalRequired("execution-close-approval", approvalCorrelation);

        actions.onConnectionClosed("desktop disconnected");

        assertThat(List.of(requested.join(), accepted.join(), previewed.join(), approval.join()))
                .extracting(PendingApplicationAction::state)
                .containsOnly(PendingApplicationAction.State.CANCELED);
        assertThat(List.of(requested.join(), accepted.join(), previewed.join(), approval.join()))
                .extracting(PendingApplicationAction::reason)
                .allMatch(reason -> reason.contains("disconnected-before-execute"));
        assertThat(terminalStore.reconciliationQueue).isEmpty();
    }

    @Test
    void sameExecutionAndCorrelationRegistrationReusesOriginalTerminalFuture() {
        PendingApplicationAction.Correlation correlation = correlation("tool-replay");

        CompletableFuture<PendingApplicationAction> first = actions.register(
                "execution-replay", correlation, PendingApplicationAction.Path.READ_ONLY);
        CompletableFuture<PendingApplicationAction> replay = actions.register(
                "execution-replay", correlation, PendingApplicationAction.Path.READ_ONLY);

        assertThat(replay).isSameAs(first);
        assertThat(actions.pendingCount()).isEqualTo(1);
    }

    @Test
    void cancelByTurnSendsCancelForEveryLiveExecutionAndUsesStateSpecificLocalOutcome() {
        List<String> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        actions.bindCancelSender(action -> {
            sent.add(action.executionId());
            return CompletableFuture.completedFuture(false);
        });
        PendingApplicationAction.Correlation preRunCorrelation = correlation("tool-cancel-pre");
        PendingApplicationAction.Correlation runningCorrelation = correlation("tool-cancel-running");
        CompletableFuture<PendingApplicationAction> preRun = actions.register(
                "execution-cancel-pre", preRunCorrelation, PendingApplicationAction.Path.HIGH_RISK);
        CompletableFuture<PendingApplicationAction> running = actions.register(
                "execution-cancel-running", runningCorrelation, PendingApplicationAction.Path.READ_ONLY);
        actions.accepted("execution-cancel-pre", preRunCorrelation);
        actions.accepted("execution-cancel-running", runningCorrelation);
        actions.running("execution-cancel-running", runningCorrelation);

        assertThat(actions.cancelByTurn("turn-1")).isEqualTo(2);

        assertThat(sent).containsExactlyInAnyOrder("execution-cancel-pre", "execution-cancel-running");
        assertThat(preRun.join().state()).isEqualTo(PendingApplicationAction.State.CANCELED);
        assertThat(running.join().state()).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertReconciliationEventuallyContains(terminalStore, running.join());
    }

    @Test
    void requestedActionWithConnectionContextCanSendOutboundCancelBeforeAccepted() {
        List<PendingApplicationAction> sent = new ArrayList<>();
        actions.bindCancelSender(action -> {
            sent.add(action);
            return CompletableFuture.completedFuture(true);
        });
        PendingApplicationAction.Correlation correlation = correlation("tool-cancel-requested");
        PendingApplicationAction.ConnectionContext context = connectionContext("ws-requested");
        CompletableFuture<PendingApplicationAction> terminal = actions.register(
                "execution-cancel-requested", correlation, PendingApplicationAction.Path.HIGH_RISK, context);

        assertThat(actions.cancelByTurn("turn-1")).isEqualTo(1);

        assertThat(sent).singleElement().extracting(PendingApplicationAction::connectionContext)
                .isEqualTo(context);
        assertThat(terminal.join().state()).isEqualTo(PendingApplicationAction.State.CANCELED);
    }

    @Test
    void confirmedRunningCancelRemainsRunningUntilDesktopTerminal() {
        actions.bindCancelSender(action -> CompletableFuture.completedFuture(true));
        PendingApplicationAction.Correlation correlation = correlation("tool-cancel-confirmed");
        CompletableFuture<PendingApplicationAction> terminal = actions.register(
                "execution-cancel-confirmed", correlation, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-confirmed"));
        actions.accepted("execution-cancel-confirmed", correlation);
        actions.running("execution-cancel-confirmed", correlation);

        actions.cancelByTurn("turn-1");

        assertThat(terminal).isNotDone();
        assertThat(actions.snapshot("execution-cancel-confirmed")).get()
                .extracting(PendingApplicationAction::state)
                .isEqualTo(PendingApplicationAction.State.RUNNING);
        assertThat(actions.terminal(
                "execution-cancel-confirmed", correlation, PendingApplicationAction.State.CANCELED, null)).isTrue();
        assertThat(terminal.join().state()).isEqualTo(PendingApplicationAction.State.CANCELED);
    }

    @Test
    void pendingSnapshotRetainsSafeConnectionContextWithoutActionInput() {
        PendingApplicationAction.Correlation correlation = correlation("tool-context");
        PendingApplicationAction.ConnectionContext context = new PendingApplicationAction.ConnectionContext(
                "reservation-1", "ws-1", "desktop-1", "desktop-session-1", "auth-session-1", 8,
                "user-1", "tenant-1", "platform-1");
        actions.register("execution-context", correlation, PendingApplicationAction.Path.READ_ONLY);

        assertThat(actions.attachConnectionContext("execution-context", correlation, context)).isTrue();

        assertThat(actions.snapshot("execution-context")).get()
                .extracting(PendingApplicationAction::connectionContext)
                .isEqualTo(context);
        assertThat(actions.snapshot("execution-context").orElseThrow().toString())
                .doesNotContain("user-1", "tenant-1", "auth-session-1");
    }

    @Test
    void connectionContextCanOnlyBeAttachedIdempotently() {
        PendingApplicationAction.Correlation correlation = correlation("tool-context-immutable");
        PendingApplicationAction.ConnectionContext original = connectionContext("ws-original");
        PendingApplicationAction.ConnectionContext replacement = connectionContext("ws-replacement");
        actions.register("execution-context-immutable", correlation, PendingApplicationAction.Path.READ_ONLY);

        assertThat(actions.attachConnectionContext("execution-context-immutable", correlation, original)).isTrue();
        assertThat(actions.attachConnectionContext("execution-context-immutable", correlation, original)).isTrue();
        assertThat(actions.attachConnectionContext("execution-context-immutable", correlation, replacement)).isFalse();

        assertThat(actions.snapshot("execution-context-immutable")).get()
                .extracting(PendingApplicationAction::connectionContext)
                .isEqualTo(original);
    }

    @Test
    void authorizedLookupBindsOnlyLiveLegacyPendingAndNeverRebindsStoredTerminal() {
        RecordingTerminalStore store = new RecordingTerminalStore();
        PendingApplicationActions local = actions(store, action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()));
        PendingApplicationAction.Correlation liveCorrelation = correlation("tool-authorized-live");
        PendingApplicationAction.ConnectionContext original = connectionContext("ws-authorized");
        PendingApplicationAction.ConnectionContext other = connectionContext("ws-other");
        local.register("execution-authorized-live", liveCorrelation, PendingApplicationAction.Path.READ_ONLY);

        assertThat(local.findAuthorized("execution-authorized-live", liveCorrelation, original)).get()
                .extracting(PendingApplicationAction::connectionContext)
                .isEqualTo(original);
        assertThat(local.findAuthorized("execution-authorized-live", liveCorrelation, other)).isEmpty();

        local.accepted("execution-authorized-live", liveCorrelation);
        local.running("execution-authorized-live", liveCorrelation);
        local.terminal("execution-authorized-live", liveCorrelation,
                PendingApplicationAction.State.COMPLETED, null);
        assertThat(local.findAuthorized("execution-authorized-live", liveCorrelation, original)).isPresent();
        assertThat(local.findAuthorized("execution-authorized-live", liveCorrelation, other)).isEmpty();

        PendingApplicationAction.Correlation legacyTerminalCorrelation = correlation("tool-legacy-terminal");
        store.recordTerminal(new PendingApplicationAction(
                "execution-legacy-terminal",
                legacyTerminalCorrelation,
                PendingApplicationAction.Path.READ_ONLY,
                PendingApplicationAction.State.COMPLETED,
                null,
                null,
                Instant.now()), false);
        assertThat(local.findAuthorized("execution-legacy-terminal", legacyTerminalCorrelation, original)).isEmpty();
    }

    @Test
    void authorizedTransitionsRejectAReplacementEntryOwnedByAnotherConnection() {
        PendingApplicationAction.Correlation correlation = correlation("tool-authorized-replacement");
        PendingApplicationAction.ConnectionContext original = connectionContext("ws-original-owner");
        PendingApplicationAction.ConnectionContext replacement = connectionContext("ws-replacement-owner");
        actions.register("execution-authorized-replacement", correlation,
                PendingApplicationAction.Path.READ_ONLY, replacement);

        assertThat(actions.acceptedAuthorized(
                "execution-authorized-replacement", correlation, original)).isFalse();
        assertThat(actions.terminalAuthorized(
                "execution-authorized-replacement", correlation, original,
                PendingApplicationAction.State.COMPLETED, null)).isFalse();

        assertThat(actions.snapshot("execution-authorized-replacement")).get()
                .satisfies(action -> {
                    assertThat(action.state()).isEqualTo(PendingApplicationAction.State.REQUESTED);
                    assertThat(action.connectionContext()).isEqualTo(replacement);
                });
    }

    @Test
    void outcomeUnknownIsRecordedAndQueuedBeforeCompletingTheWaiter() {
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        ApplicationActionTerminalStore store = new ApplicationActionTerminalStore() {
            @Override
            public Optional<PendingApplicationAction> findTerminal(
                    String executionId,
                    PendingApplicationAction.Correlation correlation) {
                return Optional.empty();
            }

            @Override
            public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
                events.add("record");
            }

            @Override
            public void queueReconciliation(PendingApplicationAction terminal) {
                events.add("queue");
            }
        };
        PendingApplicationActions local = actions(store, action ->
                CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()));
        PendingApplicationAction.Correlation correlation = correlation("tool-ordering");
        CompletableFuture<PendingApplicationAction> terminal = local.register(
                "execution-ordering", correlation, PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-ordering"));
        terminal.thenRun(() -> events.add("complete"));
        local.accepted("execution-ordering", correlation);
        local.running("execution-ordering", correlation);

        assertThat(local.cancel("execution-ordering", correlation, "test cancel")).isTrue();

        assertThat(events).containsExactly("complete", "record", "queue");
    }

    @Test
    void scopedPreExecutionExpirationOnlyExpiresTheExactOldIdentityAndLeavesRunningUntouched() {
        PendingApplicationAction.ConnectionContext oldScope = connectionContext("ws-old-scope-expire");
        PendingApplicationAction.ConnectionContext newScope = new PendingApplicationAction.ConnectionContext(
                oldScope.reservationId(), oldScope.webSocketSessionId(), oldScope.desktopInstanceId(),
                oldScope.desktopSessionId(), "auth-session-new", oldScope.identityEpoch() + 1,
                "user-new", "tenant-new", oldScope.platformId());
        PendingApplicationAction.Correlation oldPreRunCorrelation = correlation("tool-old-prerun");
        PendingApplicationAction.Correlation oldRunningCorrelation = correlation("tool-old-running");
        PendingApplicationAction.Correlation newPreRunCorrelation = correlation("tool-new-prerun");
        CompletableFuture<PendingApplicationAction> oldPreRun = actions.register(
                "execution-old-prerun", oldPreRunCorrelation,
                PendingApplicationAction.Path.HIGH_RISK, oldScope);
        CompletableFuture<PendingApplicationAction> oldRunning = actions.register(
                "execution-old-running", oldRunningCorrelation,
                PendingApplicationAction.Path.READ_ONLY, oldScope);
        CompletableFuture<PendingApplicationAction> newPreRun = actions.register(
                "execution-new-prerun", newPreRunCorrelation,
                PendingApplicationAction.Path.HIGH_RISK, newScope);
        actions.accepted("execution-old-prerun", oldPreRunCorrelation);
        actions.accepted("execution-old-running", oldRunningCorrelation);
        actions.running("execution-old-running", oldRunningCorrelation);
        actions.accepted("execution-new-prerun", newPreRunCorrelation);

        assertThat(actions.expirePreExecution(oldScope, "identity changed")).isEqualTo(1);

        assertThat(oldPreRun.join().state()).isEqualTo(PendingApplicationAction.State.EXPIRED);
        assertThat(oldRunning).isNotDone();
        assertThat(newPreRun).isNotDone();
        assertThat(actions.snapshot("execution-old-running")).get()
                .extracting(PendingApplicationAction::state)
                .isEqualTo(PendingApplicationAction.State.RUNNING);
    }

    private PendingApplicationActions actions(
            ApplicationActionTerminalStore store,
            PendingApplicationActions.StatusQuery statusQuery) {
        return new PendingApplicationActions(timeouts, store, statusQuery, scheduler, Clock.systemUTC());
    }

    private ApplicationActionTerminalStore throwingStore(
            boolean throwRecord,
            boolean throwQueue,
            boolean throwFind) {
        return new ApplicationActionTerminalStore() {
            @Override
            public Optional<PendingApplicationAction> findTerminal(
                    String executionId,
                    PendingApplicationAction.Correlation correlation) {
                if (throwFind) {
                    throw new IllegalStateException("terminal lookup unavailable");
                }
                return Optional.empty();
            }

            @Override
            public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
                if (throwRecord) {
                    throw new IllegalStateException("terminal audit unavailable");
                }
            }

            @Override
            public void queueReconciliation(PendingApplicationAction terminal) {
                if (throwQueue) {
                    throw new IllegalStateException("reconciliation queue unavailable");
                }
            }
        };
    }

    private void assertRejectedProgressKeepsPreviousState(
            String suffix,
            PendingApplicationAction.Path path,
            PendingApplicationAction.State expectedState,
            int rejectedScheduleCall,
            ProgressTransition transition) {
        AtomicInteger scheduleCalls = new AtomicInteger();
        AtomicReference<ScheduledFuture<?>> previousTimeout = new AtomicReference<>();
        AtomicReference<Runnable> previousTimeoutTask = new AtomicReference<>();
        ScheduledExecutorService rejectingScheduler = mock(ScheduledExecutorService.class);
        when(rejectingScheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    int call = scheduleCalls.incrementAndGet();
                    if (call == rejectedScheduleCall) {
                        throw new java.util.concurrent.RejectedExecutionException(suffix + " scheduler stopped");
                    }
                    ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
                    previousTimeout.set(scheduled);
                    previousTimeoutTask.set(invocation.getArgument(0));
                    return scheduled;
                });
        PendingApplicationActions local = new PendingApplicationActions(
                timeouts,
                terminalStore,
                action -> CompletableFuture.completedFuture(PendingApplicationActions.RemoteStatus.running()),
                rejectingScheduler,
                Clock.systemUTC());
        String executionId = "execution-reschedule-rejected-" + suffix;
        PendingApplicationAction.Correlation correlation = correlation("tool-reschedule-rejected-" + suffix);
        CompletableFuture<PendingApplicationAction> terminal = local.register(executionId, correlation, path);
        moveToState(local, executionId, correlation, path, expectedState);
        ScheduledFuture<?> activeTimeout = previousTimeout.get();

        assertThatThrownBy(() -> transition.apply(local, executionId, correlation))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);

        assertThat(local.snapshot(executionId)).get()
                .extracting(PendingApplicationAction::state)
                .isEqualTo(expectedState);
        assertThat(terminal).isNotDone();
        verify(activeTimeout, never()).cancel(false);

        previousTimeoutTask.get().run();

        assertThat(terminal.join().state()).isEqualTo(PendingApplicationAction.State.EXPIRED);
        assertThat(local.snapshot(executionId)).isEmpty();
    }

    private static void moveToState(
            PendingApplicationActions actions,
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.Path path,
            PendingApplicationAction.State target) {
        if (target == PendingApplicationAction.State.REQUESTED) {
            return;
        }
        assertThat(actions.accepted(executionId, correlation)).isTrue();
        if (target == PendingApplicationAction.State.ACCEPTED) {
            return;
        }
        if (path != PendingApplicationAction.Path.READ_ONLY) {
            assertThat(actions.previewed(executionId, correlation)).isTrue();
            if (target == PendingApplicationAction.State.PREVIEWED) {
                return;
            }
        }
        if (path == PendingApplicationAction.Path.HIGH_RISK) {
            assertThat(actions.approvalRequired(executionId, correlation)).isTrue();
            if (target == PendingApplicationAction.State.APPROVAL_REQUIRED) {
                return;
            }
        }
        assertThat(actions.running(executionId, correlation)).isTrue();
    }

    private static boolean terminalAllowed(
            PendingApplicationAction.Path path,
            PendingApplicationAction.State from,
            PendingApplicationAction.State terminal) {
        return switch (terminal) {
            case COMPLETED, OUTCOME_UNKNOWN -> from == PendingApplicationAction.State.RUNNING
                    || path == PendingApplicationAction.Path.READ_ONLY
                            && from == PendingApplicationAction.State.ACCEPTED
                    || path == PendingApplicationAction.Path.REVERSIBLE_WRITE
                            && from == PendingApplicationAction.State.PREVIEWED
                    || path == PendingApplicationAction.Path.HIGH_RISK
                            && from == PendingApplicationAction.State.APPROVAL_REQUIRED;
            case FAILED, CANCELED -> from == PendingApplicationAction.State.ACCEPTED
                    || from == PendingApplicationAction.State.PREVIEWED
                    || from == PendingApplicationAction.State.APPROVAL_REQUIRED
                    || from == PendingApplicationAction.State.RUNNING;
            case REJECTED -> from == PendingApplicationAction.State.ACCEPTED;
            case EXPIRED -> from == PendingApplicationAction.State.ACCEPTED
                    || from == PendingApplicationAction.State.PREVIEWED
                    || from == PendingApplicationAction.State.APPROVAL_REQUIRED
                    || from == PendingApplicationAction.State.RUNNING;
            default -> false;
        };
    }

    private static PendingApplicationAction.Correlation correlation(String toolCallId) {
        return new PendingApplicationAction.Correlation("thread-1", "turn-1", toolCallId);
    }

    private static PendingApplicationAction.ConnectionContext connectionContext(String webSocketSessionId) {
        return new PendingApplicationAction.ConnectionContext(
                "reservation-1", webSocketSessionId, "desktop-1", "desktop-session-1", "auth-session-1", 8,
                "user-1", "tenant-1", "platform-1");
    }

    private static Object pendingEntry(PendingApplicationActions actions, String executionId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) org.springframework.test.util.ReflectionTestUtils
                .getField(actions, "pending");
        return java.util.Objects.requireNonNull(pending.get(executionId));
    }

    private static void awaitBlocked(Thread thread) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1))
                .until(() -> thread.getState() == Thread.State.BLOCKED);
    }

    private static void assertReconciliationEventuallyContains(
            RecordingTerminalStore store,
            PendingApplicationAction expected) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(store.reconciliationQueue).containsExactly(expected));
    }

    private static String registerFailureAfter(
            CountDownLatch start,
            PendingApplicationActions actions,
            String suffix) throws InterruptedException {
        start.await();
        try {
            actions.register("execution-capacity-" + suffix, correlation("tool-capacity-" + suffix),
                    PendingApplicationAction.Path.READ_ONLY, connectionContext("ws-capacity-" + suffix));
            return "registered";
        } catch (IllegalStateException failure) {
            return failure.getMessage();
        }
    }

    private static void assertCapacityRejected(PendingApplicationActions actions, String suffix) {
        assertThatThrownBy(() -> actions.register(
                "execution-capacity-rejected-" + suffix,
                correlation("tool-capacity-rejected-" + suffix),
                PendingApplicationAction.Path.READ_ONLY,
                connectionContext("ws-capacity-rejected-" + suffix)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit backlog capacity");
    }

    @FunctionalInterface
    private interface ProgressTransition {
        boolean apply(
                PendingApplicationActions actions,
                String executionId,
                PendingApplicationAction.Correlation correlation);
    }

    private static class RecordingTerminalStore implements ApplicationActionTerminalStore {
        protected final List<StoredTerminal> terminals = new CopyOnWriteArrayList<>();
        protected final List<PendingApplicationAction> reconciliationQueue = new CopyOnWriteArrayList<>();

        @Override
        public synchronized Optional<PendingApplicationAction> findTerminal(
                String executionId,
                PendingApplicationAction.Correlation correlation) {
            return terminals.stream()
                    .filter(stored -> !stored.lateResult())
                    .map(StoredTerminal::terminal)
                    .filter(action -> action.executionId().equals(executionId))
                    .filter(action -> action.correlation().equals(correlation))
                    .findFirst();
        }

        @Override
        public synchronized void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
            terminals.add(new StoredTerminal(terminal, lateResult));
        }

        @Override
        public synchronized void queueReconciliation(PendingApplicationAction terminal) {
            reconciliationQueue.add(terminal);
        }
    }

    private record StoredTerminal(PendingApplicationAction terminal, boolean lateResult) {
    }

    private static final class GapDetectingTerminalStore extends RecordingTerminalStore {
        private final CountDownLatch lookupEntered = new CountDownLatch(1);
        private final CountDownLatch releaseLookup = new CountDownLatch(1);

        @Override
        public synchronized Optional<PendingApplicationAction> findTerminal(
                String executionId,
                PendingApplicationAction.Correlation correlation) {
            lookupEntered.countDown();
            BlockingAuditStore.await(releaseLookup);
            return super.findTerminal(executionId, correlation);
        }
    }

    private static final class BlockingRemoveMap extends ConcurrentHashMap<String, Object> {
        private final String blockedExecutionId;
        private final CountDownLatch removed = new CountDownLatch(1);
        private final CountDownLatch releaseRemove = new CountDownLatch(1);

        private BlockingRemoveMap(String blockedExecutionId) {
            this.blockedExecutionId = blockedExecutionId;
        }

        @Override
        public boolean remove(Object key, Object value) {
            boolean result = super.remove(key, value);
            if (result && blockedExecutionId.equals(key)) {
                removed.countDown();
                BlockingAuditStore.await(releaseRemove);
            }
            return result;
        }
    }

    private static final class BlockingAuditStore extends RecordingTerminalStore {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch acceptedEntered = new CountDownLatch(1);
        private final CountDownLatch releaseAccepted = new CountDownLatch(1);
        private final CountDownLatch terminalEntered = new CountDownLatch(1);
        private final CountDownLatch releaseTerminal = new CountDownLatch(1);
        private final AtomicBoolean blockNextTerminal = new AtomicBoolean();
        private final AtomicBoolean blockAccepted = new AtomicBoolean();
        private volatile String currentState;

        @Override
        public void recordRegistered(PendingApplicationAction requested) {
            currentState = "REQUESTED";
            events.add("REQUESTED");
        }

        @Override
        public void recordTransition(
                PendingApplicationAction previous,
                PendingApplicationAction current,
                boolean lateResult) {
            if (current.state() == PendingApplicationAction.State.ACCEPTED
                    && blockAccepted.compareAndSet(true, false)) {
                acceptedEntered.countDown();
                await(releaseAccepted);
            }
            if (current.isTerminal() && !lateResult && blockNextTerminal.compareAndSet(true, false)) {
                terminalEntered.countDown();
                await(releaseTerminal);
            }
            if (!lateResult) {
                currentState = dbState(current.state());
            }
            events.add(dbState(current.state()) + (lateResult ? ":late" : ""));
            if (current.isTerminal()) {
                super.recordTerminal(current, lateResult);
            }
        }

        @Override
        public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
            recordTransition(null, terminal, lateResult);
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("audit store interrupted", interrupted);
            }
        }
    }

    private static final class FailingAuditStore extends RecordingTerminalStore {
        private final int acceptedFailures;
        private final AtomicInteger recordAttempts = new AtomicInteger();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private volatile String currentState;

        private FailingAuditStore(int acceptedFailures) {
            this.acceptedFailures = acceptedFailures;
        }

        @Override
        public void recordRegistered(PendingApplicationAction requested) {
            currentState = "REQUESTED";
            events.add("REQUESTED");
        }

        @Override
        public void recordTransition(
                PendingApplicationAction previous,
                PendingApplicationAction current,
                boolean lateResult) {
            if (current.state() == PendingApplicationAction.State.ACCEPTED
                    && recordAttempts.incrementAndGet() <= acceptedFailures) {
                throw new IllegalStateException("transient audit failure");
            }
            if (!lateResult) {
                currentState = dbState(current.state());
            }
            events.add(dbState(current.state()) + (lateResult ? ":late" : ""));
            if (current.isTerminal()) {
                super.recordTerminal(current, lateResult);
            }
        }

        @Override
        public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
            recordTransition(null, terminal, lateResult);
        }
    }

    private static final class SwitchableAuditStore extends RecordingTerminalStore {
        private final AtomicBoolean fail = new AtomicBoolean(true);

        @Override
        public void recordRegistered(PendingApplicationAction requested) {
            // Registration is the hard durable prerequisite and succeeds while transition audit is unavailable.
        }

        @Override
        public void recordTransition(
                PendingApplicationAction previous,
                PendingApplicationAction current,
                boolean lateResult) {
            if (fail.get()) {
                throw new IllegalStateException("audit unavailable");
            }
            if (current.isTerminal()) {
                super.recordTerminal(current, lateResult);
            }
        }

        @Override
        public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
            recordTransition(null, terminal, lateResult);
        }
    }

    private static final class SwitchableEventAuditStore extends RecordingTerminalStore {
        private final AtomicBoolean fail = new AtomicBoolean();
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void recordRegistered(PendingApplicationAction requested) {
            events.add("REQUESTED");
        }

        @Override
        public void recordTransition(
                PendingApplicationAction previous,
                PendingApplicationAction current,
                boolean lateResult) {
            if (fail.get()) {
                throw new IllegalStateException("audit unavailable");
            }
            events.add(dbState(current.state()) + (lateResult ? ":late" : ""));
        }

        @Override
        public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
            recordTransition(null, terminal, lateResult);
        }
    }

    private static final class BlockingThenFailingAuditStore extends RecordingTerminalStore {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final List<String> events = new CopyOnWriteArrayList<>(List.of("REQUESTED"));

        @Override
        public void recordRegistered(PendingApplicationAction requested) {
            // Initial event is represented by the seeded list entry.
        }

        @Override
        public void recordTransition(
                PendingApplicationAction previous,
                PendingApplicationAction current,
                boolean lateResult) {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                BlockingAuditStore.await(release);
                throw new IllegalStateException("first drainer failed");
            }
            events.add(dbState(current.state()));
        }

        @Override
        public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
            recordTransition(null, terminal, lateResult);
        }
    }

    private static final class HistoricalTerminalStore extends RecordingTerminalStore {
        private final Map<String, PendingApplicationAction> historical = new ConcurrentHashMap<>();
        private final AtomicBoolean fail = new AtomicBoolean();
        private final List<String> lateExecutionIds = new CopyOnWriteArrayList<>();
        private final AtomicBoolean blockLookup = new AtomicBoolean();
        private final CountDownLatch lookupEntered = new CountDownLatch(1);
        private final CountDownLatch releaseLookup = new CountDownLatch(1);
        private final AtomicInteger lateFailures = new AtomicInteger();

        private void putHistorical(String executionId, PendingApplicationAction.Correlation correlation) {
            historical.put(executionId, new PendingApplicationAction(
                    executionId, correlation, PendingApplicationAction.Path.READ_ONLY,
                    PendingApplicationAction.State.COMPLETED, null, null, Instant.now(), null));
        }

        @Override
        public Optional<PendingApplicationAction> findTerminal(
                String executionId, PendingApplicationAction.Correlation correlation) {
            if (blockLookup.get()) {
                lookupEntered.countDown();
                BlockingAuditStore.await(releaseLookup);
            }
            return Optional.ofNullable(historical.get(executionId))
                    .filter(action -> action.correlation().equals(correlation));
        }

        @Override
        public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
            if (fail.get() || lateFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("historical late audit unavailable");
            }
            if (lateResult) {
                lateExecutionIds.add(terminal.executionId());
            }
        }
    }

    private static final class RetirementRaceStore extends RecordingTerminalStore {
        private final CountDownLatch terminalEntered = new CountDownLatch(1);
        private final CountDownLatch releaseTerminal = new CountDownLatch(1);
        private final AtomicBoolean firstTerminal = new AtomicBoolean(true);
        private final AtomicBoolean failLate = new AtomicBoolean();

        @Override
        public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
            if (!lateResult && firstTerminal.compareAndSet(true, false)) {
                terminalEntered.countDown();
                BlockingAuditStore.await(releaseTerminal);
            }
            if (lateResult && failLate.get()) {
                throw new IllegalStateException("late audit unavailable");
            }
            super.recordTerminal(terminal, lateResult);
        }
    }

    private static final class OneShotThrowingClock extends Clock {
        private final AtomicBoolean throwNext = new AtomicBoolean(true);

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (throwNext.compareAndSet(true, false)) {
                throw new IllegalStateException("clock unavailable");
            }
            return Instant.now();
        }
    }

    private static final class ArmableThrowingClock extends Clock {
        private final AtomicBoolean throwNext = new AtomicBoolean();

        private void throwNext() {
            throwNext.set(true);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (throwNext.compareAndSet(true, false)) {
                throw new IllegalStateException("clock unavailable");
            }
            return Instant.now();
        }
    }

    private static final class ManualScheduledExecutor extends java.util.concurrent.ScheduledThreadPoolExecutor {
        private final Queue<ManualScheduledFuture> scheduled = new ConcurrentLinkedQueue<>();

        private ManualScheduledExecutor() {
            super(0);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ManualScheduledFuture future = new ManualScheduledFuture(command, unit.toNanos(delay));
            scheduled.add(future);
            return future;
        }

        private void runNextRetry() {
            ManualScheduledFuture retry = scheduled.stream()
                    .filter(task -> !task.isCancelled() && !task.isDone())
                    .filter(task -> task.delayNanos <= TimeUnit.MILLISECONDS.toNanos(10))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no audit retry scheduled"));
            retry.run();
        }

        private void runAllRetries() {
            for (int index = 0; index < 10; index++) {
                Optional<ManualScheduledFuture> retry = scheduled.stream()
                        .filter(task -> !task.isCancelled() && !task.isDone())
                        .filter(task -> task.delayNanos <= TimeUnit.MILLISECONDS.toNanos(10))
                        .findFirst();
                if (retry.isEmpty()) {
                    return;
                }
                retry.orElseThrow().run();
            }
            throw new AssertionError("audit retry budget is not bounded");
        }
    }

    private static final class ManualScheduledFuture implements ScheduledFuture<Object>, Runnable {
        private final Runnable command;
        private final long delayNanos;
        private volatile boolean cancelled;
        private volatile boolean done;

        private ManualScheduledFuture(Runnable command, long delayNanos) {
            this.command = command;
            this.delayNanos = delayNanos;
        }

        @Override
        public void run() {
            if (!cancelled && !done) {
                done = true;
                command.run();
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delayNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return Long.compare(delayNanos, other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }

    private static String dbState(PendingApplicationAction.State state) {
        return state == PendingApplicationAction.State.RUNNING ? "EXECUTING" : state.name();
    }
}
