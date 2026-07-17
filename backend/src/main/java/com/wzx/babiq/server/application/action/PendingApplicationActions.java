package com.wzx.babiq.server.application.action;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** 管理服务端已发出、正等待桌面进度或终态的应用动作。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class PendingApplicationActions implements AutoCloseable {

    private final ApplicationActionTimeoutProperties timeouts;
    private final ApplicationActionTerminalStore terminalStore;
    private final ScheduledExecutorService scheduler;
    private final boolean ownedScheduler;
    private final Clock clock;
    private final Object lifecycleLock = new Object();
    private final Map<String, Entry> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile StatusQuery statusQuery;
    private volatile CancelSender cancelSender = action -> CompletableFuture.failedFuture(
            new IllegalStateException("application action cancel sender is unavailable"));

    @Autowired
    public PendingApplicationActions(
            ApplicationActionTimeoutProperties timeouts,
            ObjectProvider<ApplicationActionTerminalStore> terminalStoreProvider,
            ObjectProvider<StatusQuery> statusQueryProvider) {
        this(timeouts,
                terminalStoreProvider.getIfAvailable(BoundedVolatileTerminalStore::new),
                action -> CompletableFuture.failedFuture(
                        new IllegalStateException("application action status query is unavailable")),
                Executors.newSingleThreadScheduledExecutor(daemonThreadFactory()), Clock.systemUTC(), true);
    }

    public PendingApplicationActions(
            ApplicationActionTimeoutProperties timeouts,
            ApplicationActionTerminalStore terminalStore,
            StatusQuery statusQuery,
            ScheduledExecutorService scheduler,
            Clock clock) {
        this(timeouts, terminalStore, statusQuery, scheduler, clock, false);
    }

    private PendingApplicationActions(
            ApplicationActionTimeoutProperties timeouts,
            ApplicationActionTerminalStore terminalStore,
            StatusQuery statusQuery,
            ScheduledExecutorService scheduler,
            Clock clock,
            boolean ownedScheduler) {
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.terminalStore = Objects.requireNonNull(terminalStore, "terminalStore");
        this.statusQuery = Objects.requireNonNull(statusQuery, "statusQuery");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownedScheduler = ownedScheduler;
    }

    public CompletableFuture<PendingApplicationAction> register(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.Path path) {
        return register(executionId, correlation, path, null);
    }

    public CompletableFuture<PendingApplicationAction> register(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.Path path,
            PendingApplicationAction.ConnectionContext connectionContext) {
        PendingApplicationAction requested = new PendingApplicationAction(
                executionId,
                correlation,
                path,
                PendingApplicationAction.State.REQUESTED,
                null,
                null,
                clock.instant(),
                connectionContext);
        Entry candidate = new Entry(requested);
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("Pending application actions is closed");
            }
            synchronized (candidate) {
                Entry existing = pending.putIfAbsent(executionId, candidate);
                if (existing != null) {
                    synchronized (existing) {
                        if (existing.action.correlation().equals(correlation)
                                && existing.action.path() == path
                                && Objects.equals(existing.action.connectionContext(), connectionContext)) {
                            return existing.terminal;
                        }
                    }
                    throw new IllegalStateException("Conflicting application executionId: " + executionId);
                }
                try {
                    schedule(candidate, timeouts.acceptTimeout(),
                            PendingApplicationAction.State.REQUESTED, "accept timeout");
                } catch (RuntimeException failure) {
                    pending.remove(executionId, candidate);
                    candidate.terminal.completeExceptionally(failure);
                    throw failure;
                }
                return candidate.terminal;
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            onConnectionClosed("pending application actions closed");
            if (ownedScheduler) {
                scheduler.shutdownNow();
            }
        }
    }

    public Optional<PendingApplicationAction> snapshot(String executionId) {
        Entry entry = pending.get(executionId);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            return Optional.of(entry.action);
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    public boolean attachConnectionContext(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext context) {
        Entry entry = pending.get(executionId);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (!entry.matches(correlation) || pending.get(executionId) != entry) {
                return false;
            }
            PendingApplicationAction.ConnectionContext existing = entry.action.connectionContext();
            if (existing != null) {
                return existing.equals(context);
            }
            entry.action = entry.action.withConnectionContext(Objects.requireNonNull(context, "context"));
            return true;
        }
    }

    /** Task 24 协议适配器构造完成后绑定真实 status query，避免 Spring 循环依赖。 */
    public void bindStatusQuery(StatusQuery statusQuery) {
        this.statusQuery = Objects.requireNonNull(statusQuery, "statusQuery");
    }

    /** Task 24 协议适配器构造完成后绑定真实 cancel sender。 */
    public void bindCancelSender(CancelSender cancelSender) {
        this.cancelSender = Objects.requireNonNull(cancelSender, "cancelSender");
    }

    /** 查询当前 pending 或 first terminal，并强制匹配完整 correlation。 */
    public Optional<PendingApplicationAction> find(
            String executionId,
            PendingApplicationAction.Correlation correlation) {
        Optional<PendingApplicationAction> current = snapshot(executionId)
                .filter(action -> action.correlation().equals(correlation));
        return current.isPresent() ? current : terminalStore.findTerminal(executionId, correlation);
    }

    /**
     * 在连接身份边界内查找动作。仅仍在 live pending 且尚未绑定的兼容记录允许首次绑定；
     * 已持久化但缺少连接上下文的终态不能借查询补绑定。
     */
    public Optional<PendingApplicationAction> findAuthorized(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext) {
        Objects.requireNonNull(connectionContext, "connectionContext");
        Entry entry = pending.get(executionId);
        if (entry != null) {
            synchronized (entry) {
                if (!entry.matches(correlation) || pending.get(executionId) != entry) {
                    return Optional.empty();
                }
                PendingApplicationAction.ConnectionContext stored = entry.action.connectionContext();
                if (stored == null) {
                    entry.action = entry.action.withConnectionContext(connectionContext);
                    return Optional.of(entry.action);
                }
                return stored.equals(connectionContext) ? Optional.of(entry.action) : Optional.empty();
            }
        }
        return terminalStore.findTerminal(executionId, correlation)
                .filter(action -> connectionContext.equals(action.connectionContext()));
    }

    /** Turn 结束前先向桌面发送取消；pre-run 本地取消，RUNNING 发送失败即进入结果未知。 */
    public int cancelByTurn(String turnId) {
        int matched = 0;
        for (Entry entry : pending.values()) {
            PendingApplicationAction action;
            synchronized (entry) {
                if (!turnId.equals(entry.action.correlation().turnId()) || entry.action.isTerminal()) {
                    continue;
                }
                action = entry.action;
                matched++;
            }
            CompletableFuture<Boolean> sent;
            try {
                sent = cancelSender.send(action);
            } catch (RuntimeException failure) {
                sent = CompletableFuture.failedFuture(failure);
            }
            if (action.state() != PendingApplicationAction.State.RUNNING) {
                cancel(action.executionId(), action.correlation(), "turn canceled before execute");
                continue;
            }
            if (sent == null) {
                markRunningCancelUnconfirmed(entry, "application action cancel send failed");
                continue;
            }
            sent.whenComplete((confirmed, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(confirmed)) {
                    markRunningCancelUnconfirmed(entry, "application action cancel was not confirmed");
                }
            });
        }
        return matched;
    }

    /** 供后续生命周期协调器按完整旧身份 scope 过期尚未开始执行的动作。 */
    public int expirePreExecution(
            PendingApplicationAction.ConnectionContext scope,
            String reason) {
        Objects.requireNonNull(scope, "scope");
        int expired = 0;
        for (Entry entry : pending.values()) {
            synchronized (entry) {
                if (pending.get(entry.action.executionId()) != entry
                        || entry.action.state() == PendingApplicationAction.State.RUNNING
                        || entry.action.isTerminal()
                        || !scope.equals(entry.action.connectionContext())) {
                    continue;
                }
                PendingApplicationAction terminal = entry.action.toTerminal(
                        PendingApplicationAction.State.EXPIRED, null, reason, clock.instant());
                if (finish(entry, terminal, false, false)) {
                    expired++;
                }
            }
        }
        return expired;
    }

    public boolean accepted(String executionId, PendingApplicationAction.Correlation correlation) {
        return acceptedAuthorized(executionId, correlation, null);
    }

    public boolean acceptedAuthorized(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext) {
        return transition(
                executionId,
                correlation,
                connectionContext,
                PendingApplicationAction.State.REQUESTED,
                PendingApplicationAction.State.ACCEPTED,
                action -> action.path() == PendingApplicationAction.Path.READ_ONLY
                        ? new TimeoutSpec(timeouts.executeTimeout(), "running timeout")
                        : new TimeoutSpec(timeouts.previewTimeout(), "preview timeout"));
    }

    public boolean previewed(String executionId, PendingApplicationAction.Correlation correlation) {
        return previewedAuthorized(executionId, correlation, null);
    }

    public boolean previewedAuthorized(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext) {
        return transition(
                executionId,
                correlation,
                connectionContext,
                PendingApplicationAction.State.ACCEPTED,
                PendingApplicationAction.State.PREVIEWED,
                action -> action.path() == PendingApplicationAction.Path.HIGH_RISK
                        ? new TimeoutSpec(timeouts.approvalTimeout(), "approval timeout")
                        : new TimeoutSpec(timeouts.previewTimeout(), "running timeout"));
    }

    public boolean approvalRequired(String executionId, PendingApplicationAction.Correlation correlation) {
        return approvalRequiredAuthorized(executionId, correlation, null);
    }

    public boolean approvalRequiredAuthorized(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext) {
        return transition(
                executionId,
                correlation,
                connectionContext,
                PendingApplicationAction.State.PREVIEWED,
                PendingApplicationAction.State.APPROVAL_REQUIRED,
                action -> new TimeoutSpec(timeouts.approvalTimeout(), "approval timeout"));
    }

    public boolean running(String executionId, PendingApplicationAction.Correlation correlation) {
        return runningAuthorized(executionId, correlation, null);
    }

    public boolean runningAuthorized(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext) {
        Entry entry = pending.get(executionId);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (pending.get(executionId) != entry
                    || !entry.matches(correlation)
                    || !entry.authorized(connectionContext)
                    || !mayEnterRunning(entry.action)) {
                return false;
            }
            PendingApplicationAction current = entry.action;
            PendingApplicationAction running = current.transition(
                    PendingApplicationAction.State.RUNNING, null, clock.instant());
            ScheduledFuture<?> replacement = createTimeout(
                    entry, timeouts.executeTimeout(), PendingApplicationAction.State.RUNNING, "execute timeout");
            if (pending.get(executionId) != entry || entry.action != current) {
                replacement.cancel(false);
                return false;
            }
            entry.action = running;
            entry.replaceTimeout(replacement);
            return true;
        }
    }

    public boolean terminal(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.State terminalState,
            JsonNode payload) {
        return terminalAuthorized(executionId, correlation, null, terminalState, payload);
    }

    public boolean terminalAuthorized(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext,
            PendingApplicationAction.State terminalState,
            JsonNode payload) {
        if (terminalState == null || !terminalState.isTerminal()) {
            return false;
        }
        Entry entry = pending.get(executionId);
        if (entry == null) {
            PendingApplicationAction late = terminalStore.findTerminal(executionId, correlation)
                    .filter(first -> connectionContext == null
                            || connectionContext.equals(first.connectionContext()))
                    .map(first -> first.toTerminal(terminalState, payload, "late desktop terminal", clock.instant()))
                    .orElse(null);
            if (late != null) {
                terminalStore.recordTerminal(late, true);
            }
            return false;
        }
        synchronized (entry) {
            if (pending.get(executionId) != entry
                    || !entry.matches(correlation)
                    || !entry.authorized(connectionContext)
                    || !terminalAllowed(entry.action.state(), terminalState)) {
                return false;
            }
            PendingApplicationAction.State effectiveState = entry.action.state() == PendingApplicationAction.State.RUNNING
                    && terminalState == PendingApplicationAction.State.EXPIRED
                    ? PendingApplicationAction.State.OUTCOME_UNKNOWN
                    : terminalState;
            PendingApplicationAction completed = entry.action.toTerminal(
                    effectiveState,
                    payload,
                    effectiveState == terminalState ? null : "desktop expired after execution may have started",
                    clock.instant());
            if (pending.get(executionId) != entry) {
                terminalStore.recordTerminal(completed, true);
                return false;
            }
            return finish(entry, completed, false,
                    effectiveState == PendingApplicationAction.State.OUTCOME_UNKNOWN);
        }
    }

    public boolean cancel(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            String reason) {
        Entry entry = pending.get(executionId);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (pending.get(executionId) != entry || !entry.matches(correlation)) {
                return false;
            }
            PendingApplicationAction.State terminal = entry.action.state() == PendingApplicationAction.State.RUNNING
                    ? PendingApplicationAction.State.OUTCOME_UNKNOWN
                    : PendingApplicationAction.State.CANCELED;
            PendingApplicationAction completed = entry.action.toTerminal(terminal, null, reason, clock.instant());
            return finish(entry, completed, false,
                    terminal == PendingApplicationAction.State.OUTCOME_UNKNOWN);
        }
    }

    public void onConnectionClosed(String reason) {
        onConnectionClosed(null, reason);
    }

    public void onConnectionClosed(String webSocketSessionId, String reason) {
        for (Entry entry : pending.values()) {
            synchronized (entry) {
                if (entry.action.isTerminal()) {
                    continue;
                }
                if (webSocketSessionId != null) {
                    PendingApplicationAction.ConnectionContext context = entry.action.connectionContext();
                    if (context == null || !webSocketSessionId.equals(context.webSocketSessionId())) {
                        continue;
                    }
                }
                boolean running = entry.action.state() == PendingApplicationAction.State.RUNNING;
                String terminalReason = running
                        ? reason
                        : "disconnected-before-execute: " + reason;
                PendingApplicationAction completed = entry.action.toTerminal(
                        running
                                ? PendingApplicationAction.State.OUTCOME_UNKNOWN
                                : PendingApplicationAction.State.CANCELED,
                        null,
                        terminalReason,
                        clock.instant());
                finish(entry, completed, false, running);
            }
        }
    }

    private boolean transition(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext,
            PendingApplicationAction.State expected,
            PendingApplicationAction.State next,
            TimeoutResolver timeoutResolver) {
        Entry entry = pending.get(executionId);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (pending.get(executionId) != entry
                    || !entry.matches(correlation)
                    || !entry.authorized(connectionContext)
                    || entry.action.state() != expected) {
                return false;
            }
            PendingApplicationAction current = entry.action;
            PendingApplicationAction transitioned = current.transition(next, null, clock.instant());
            TimeoutSpec timeout = timeoutResolver.resolve(transitioned);
            ScheduledFuture<?> replacement = createTimeout(
                    entry, timeout.duration(), next, timeout.reason());
            if (pending.get(executionId) != entry || entry.action != current) {
                replacement.cancel(false);
                return false;
            }
            entry.action = transitioned;
            entry.replaceTimeout(replacement);
            return true;
        }
    }

    private void schedule(
            Entry entry,
            Duration timeout,
            PendingApplicationAction.State expectedState,
            String reason) {
        entry.replaceTimeout(createTimeout(entry, timeout, expectedState, reason));
    }

    private ScheduledFuture<?> createTimeout(
            Entry entry,
            Duration timeout,
            PendingApplicationAction.State expectedState,
            String reason) {
        return Objects.requireNonNull(scheduler.schedule(
                () -> onTimeout(entry, expectedState, reason),
                timeout.toNanos(),
                TimeUnit.NANOSECONDS), "scheduler returned null timeout");
    }

    private void onTimeout(Entry entry, PendingApplicationAction.State expectedState, String reason) {
        synchronized (entry) {
            if (entry.action.state() != expectedState || pending.get(entry.action.executionId()) != entry) {
                return;
            }
            if (expectedState == PendingApplicationAction.State.RUNNING) {
                reconcileRunningTimeout(entry);
                return;
            }
            finish(entry, entry.action.toTerminal(
                    PendingApplicationAction.State.EXPIRED, null, reason, clock.instant()), false, false);
        }
    }

    private void reconcileRunningTimeout(Entry entry) {
        CompletableFuture<RemoteStatus> query;
        try {
            query = statusQuery.query(entry.action);
        } catch (RuntimeException failure) {
            scheduleOutcomeUnknown(entry);
            return;
        }
        if (query == null) {
            scheduleOutcomeUnknown(entry);
            return;
        }
        query.whenComplete((status, failure) -> {
            synchronized (entry) {
                if (!isCurrentRunning(entry)) {
                    return;
                }
                Optional<PendingApplicationAction> stored = terminalStore.findTerminal(
                        entry.action.executionId(), entry.action.correlation());
                if (stored.filter(terminal -> matchesStoredTerminal(entry.action, terminal)).isPresent()) {
                    adoptStoredTerminal(entry, stored.orElseThrow());
                    return;
                }
                if (failure == null && status != null && status.terminal() != null) {
                    PendingApplicationAction.State remote = status.terminal();
                    if (!terminalAllowed(PendingApplicationAction.State.RUNNING, remote)) {
                        scheduleOutcomeUnknown(entry);
                        return;
                    }
                    PendingApplicationAction.State effective = remote == PendingApplicationAction.State.EXPIRED
                            ? PendingApplicationAction.State.OUTCOME_UNKNOWN
                            : remote;
                    finish(entry, entry.action.toTerminal(
                                    effective, status.payload(), "status query terminal", clock.instant()),
                            false,
                            effective == PendingApplicationAction.State.OUTCOME_UNKNOWN);
                    return;
                }
                scheduleOutcomeUnknown(entry);
            }
        });
    }

    private void markRunningCancelUnconfirmed(Entry entry, String reason) {
        synchronized (entry) {
            if (!isCurrentRunning(entry)) {
                return;
            }
            PendingApplicationAction unknown = entry.action.toTerminal(
                    PendingApplicationAction.State.OUTCOME_UNKNOWN, null, reason, clock.instant());
            finish(entry, unknown, false, true);
        }
    }

    private void scheduleOutcomeUnknown(Entry entry) {
        if (!isCurrentRunning(entry)) {
            return;
        }
        ScheduledFuture<?> replacement;
        try {
            replacement = Objects.requireNonNull(scheduler.schedule(() -> {
                synchronized (entry) {
                    if (!isCurrentRunning(entry)) {
                        return;
                    }
                    Optional<PendingApplicationAction> stored = terminalStore.findTerminal(
                            entry.action.executionId(), entry.action.correlation());
                    if (stored.filter(terminal -> matchesStoredTerminal(entry.action, terminal)).isPresent()) {
                        adoptStoredTerminal(entry, stored.orElseThrow());
                        return;
                    }
                    PendingApplicationAction unknown = entry.action.toTerminal(
                            PendingApplicationAction.State.OUTCOME_UNKNOWN,
                            null,
                            "reconciliation grace timeout",
                            clock.instant());
                    finish(entry, unknown, false, true);
                }
            }, timeouts.reconciliationGraceTimeout().toNanos(), TimeUnit.NANOSECONDS),
                    "scheduler returned null reconciliation grace timeout");
        } catch (RuntimeException failure) {
            if (isCurrentRunning(entry)) {
                PendingApplicationAction unknown = entry.action.toTerminal(
                        PendingApplicationAction.State.OUTCOME_UNKNOWN,
                        null,
                        "reconciliation grace schedule failed",
                        clock.instant());
                finish(entry, unknown, false, true);
            }
            return;
        }
        if (!isCurrentRunning(entry)) {
            replacement.cancel(false);
            return;
        }
        entry.replaceTimeout(replacement);
    }

    private boolean finish(
            Entry entry,
            PendingApplicationAction terminal,
            boolean lateResult,
            boolean queueReconciliation) {
        if (!pending.remove(entry.action.executionId(), entry)) {
            if (lateResult) {
                terminalStore.recordTerminal(terminal, true);
            }
            return false;
        }
        entry.cancelTimeout();
        entry.action = terminal;
        terminalStore.recordTerminal(terminal, lateResult);
        if (queueReconciliation) {
            terminalStore.queueReconciliation(terminal);
        }
        return entry.terminal.complete(terminal);
    }

    private boolean adoptStoredTerminal(Entry entry, PendingApplicationAction terminal) {
        if (!matchesStoredTerminal(entry.action, terminal)) {
            return false;
        }
        if (!pending.remove(entry.action.executionId(), entry)) {
            return false;
        }
        entry.cancelTimeout();
        entry.action = terminal;
        return entry.terminal.complete(terminal);
    }

    private static boolean matchesStoredTerminal(
            PendingApplicationAction current,
            PendingApplicationAction stored) {
        return stored != null
                && stored.isTerminal()
                && current.executionId().equals(stored.executionId())
                && current.correlation().equals(stored.correlation())
                && current.path() == stored.path()
                && current.connectionContext() != null
                && current.connectionContext().equals(stored.connectionContext());
    }

    private boolean isCurrentRunning(Entry entry) {
        return pending.get(entry.action.executionId()) == entry
                && entry.action.state() == PendingApplicationAction.State.RUNNING;
    }

    private static boolean mayEnterRunning(PendingApplicationAction action) {
        return switch (action.path()) {
            case READ_ONLY -> action.state() == PendingApplicationAction.State.ACCEPTED;
            case REVERSIBLE_WRITE -> action.state() == PendingApplicationAction.State.PREVIEWED;
            case HIGH_RISK -> action.state() == PendingApplicationAction.State.APPROVAL_REQUIRED;
        };
    }

    private static boolean terminalAllowed(
            PendingApplicationAction.State from,
            PendingApplicationAction.State terminal) {
        return switch (terminal) {
            case COMPLETED, OUTCOME_UNKNOWN -> from == PendingApplicationAction.State.RUNNING;
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

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "pending-application-actions");
            thread.setDaemon(true);
            return thread;
        };
    }

    @FunctionalInterface
    public interface StatusQuery {
        CompletableFuture<RemoteStatus> query(PendingApplicationAction action);
    }

    @FunctionalInterface
    public interface CancelSender {
        CompletableFuture<Boolean> send(PendingApplicationAction action);
    }

    public record RemoteStatus(PendingApplicationAction.State terminal, JsonNode payload) {
        public RemoteStatus {
            if (terminal != null && !terminal.isTerminal()) {
                throw new IllegalArgumentException("remote terminal state is not terminal");
            }
            payload = payload == null ? null : payload.deepCopy();
        }

        @Override
        public JsonNode payload() {
            return payload == null ? null : payload.deepCopy();
        }

        public static RemoteStatus running() {
            return new RemoteStatus(null, null);
        }

        public static RemoteStatus terminal(PendingApplicationAction.State terminal) {
            return terminal(terminal, null);
        }

        public static RemoteStatus terminal(PendingApplicationAction.State terminal, JsonNode payload) {
            return new RemoteStatus(Objects.requireNonNull(terminal, "terminal"), payload);
        }
    }

    @FunctionalInterface
    private interface TimeoutResolver {
        TimeoutSpec resolve(PendingApplicationAction action);
    }

    private record TimeoutSpec(Duration duration, String reason) {
    }

    /** Task 28 前的进程内 fallback；容量受限且不承诺重启持久化。 */
    private static final class BoundedVolatileTerminalStore implements ApplicationActionTerminalStore {
        private static final int CAPACITY = 1_024;
        private final LinkedHashMap<String, PendingApplicationAction> firstTerminals = new LinkedHashMap<>();

        @Override
        public synchronized Optional<PendingApplicationAction> findTerminal(
                String executionId,
                PendingApplicationAction.Correlation correlation) {
            PendingApplicationAction terminal = firstTerminals.get(executionId);
            return terminal != null && terminal.correlation().equals(correlation)
                    ? Optional.of(terminal)
                    : Optional.empty();
        }

        @Override
        public synchronized void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
            if (!lateResult) {
                firstTerminals.putIfAbsent(terminal.executionId(), terminal);
                evictOldest();
            }
        }

        @Override
        public void queueReconciliation(PendingApplicationAction terminal) {
            // Volatile fallback keeps the first terminal only; durable reconciliation arrives in Task 28.
        }

        private void evictOldest() {
            while (firstTerminals.size() > CAPACITY) {
                firstTerminals.remove(firstTerminals.keySet().iterator().next());
            }
        }
    }

    private static final class Entry {
        private PendingApplicationAction action;
        private final CompletableFuture<PendingApplicationAction> terminal = new CompletableFuture<>();
        private ScheduledFuture<?> timeout;

        private Entry(PendingApplicationAction action) {
            this.action = action;
        }

        private boolean matches(PendingApplicationAction.Correlation correlation) {
            return action.correlation().equals(correlation);
        }

        private boolean authorized(PendingApplicationAction.ConnectionContext connectionContext) {
            return connectionContext == null || connectionContext.equals(action.connectionContext());
        }

        private void cancelTimeout() {
            if (timeout != null) {
                timeout.cancel(false);
                timeout = null;
            }
        }

        private void replaceTimeout(ScheduledFuture<?> replacement) {
            ScheduledFuture<?> previous = timeout;
            timeout = Objects.requireNonNull(replacement, "replacement");
            if (previous != null) {
                previous.cancel(false);
            }
        }
    }
}
