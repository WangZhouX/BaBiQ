package com.wzx.babiq.server.application.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 管理服务端已发出、正等待桌面进度或终态的应用动作。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class PendingApplicationActions implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PendingApplicationActions.class);
    private static final long AUDIT_RETRY_BASE_MILLIS = 1L;
    private static final long AUDIT_RETRY_MAX_MILLIS = 1_024L;
    private static final int DEFAULT_COMPLETED_RETENTION_CAPACITY = 4_096;
    private final ApplicationActionTimeoutProperties timeouts;
    private final ApplicationActionTerminalStore terminalStore;
    private final ScheduledExecutorService scheduler;
    private final boolean ownedScheduler;
    private final Clock clock;
    private final int auditBacklogCapacity;
    private final Object lifecycleLock = new Object();
    private final Object indexLock = new Object();
    private final Map<String, Entry> pending = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Entry> completed = new LinkedHashMap<>();
    private int activeAdmissions;
    private int pendingLateAudits;
    private volatile boolean closed;
    private int inFlightLateAdmissions;
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
                Executors.newSingleThreadScheduledExecutor(daemonThreadFactory()), Clock.systemUTC(), true,
                DEFAULT_COMPLETED_RETENTION_CAPACITY);
    }

    public PendingApplicationActions(
            ApplicationActionTimeoutProperties timeouts,
            ApplicationActionTerminalStore terminalStore,
            StatusQuery statusQuery,
            ScheduledExecutorService scheduler,
            Clock clock) {
        this(timeouts, terminalStore, statusQuery, scheduler, clock, false,
                DEFAULT_COMPLETED_RETENTION_CAPACITY);
    }

    PendingApplicationActions(
            ApplicationActionTimeoutProperties timeouts,
            ApplicationActionTerminalStore terminalStore,
            StatusQuery statusQuery,
            ScheduledExecutorService scheduler,
            Clock clock,
            int completedRetentionCapacity) {
        this(timeouts, terminalStore, statusQuery, scheduler, clock, false, completedRetentionCapacity);
    }

    private PendingApplicationActions(
            ApplicationActionTimeoutProperties timeouts,
            ApplicationActionTerminalStore terminalStore,
            StatusQuery statusQuery,
            ScheduledExecutorService scheduler,
            Clock clock,
            boolean ownedScheduler,
            int completedRetentionCapacity) {
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.terminalStore = Objects.requireNonNull(terminalStore, "terminalStore");
        this.statusQuery = Objects.requireNonNull(statusQuery, "statusQuery");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownedScheduler = ownedScheduler;
        if (completedRetentionCapacity <= 0) {
            throw new IllegalArgumentException("completedRetentionCapacity must be positive");
        }
        this.auditBacklogCapacity = completedRetentionCapacity;
    }

    public CompletableFuture<PendingApplicationAction> register(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.Path path) {
        return registerInternal(executionId, correlation, path, null, null, null);
    }

    /** 注册动作并订阅后续生命周期快照，进度 listener 的异常不会打断状态机。 */
    public CompletableFuture<PendingApplicationAction> register(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.Path path,
            PendingApplicationAction.ConnectionContext connectionContext,
            Consumer<PendingApplicationAction> progressListener) {
        CompletableFuture<PendingApplicationAction> terminal =
                registerInternal(executionId, correlation, path, connectionContext, null, progressListener);
        return terminal;
    }

    /** 注册动作时同时冻结已验证的目录元数据和不可逆请求指纹。 */
    public CompletableFuture<PendingApplicationAction> register(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.Path path,
            PendingApplicationAction.ConnectionContext connectionContext,
            RegistrationMetadata registrationMetadata,
            Consumer<PendingApplicationAction> progressListener) {
        return registerInternal(
                executionId, correlation, path, connectionContext,
                Objects.requireNonNull(registrationMetadata, "registrationMetadata"), progressListener);
    }

    public CompletableFuture<PendingApplicationAction> register(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.Path path,
            PendingApplicationAction.ConnectionContext connectionContext) {
        return registerInternal(executionId, correlation, path, connectionContext, null, null);
    }

    private CompletableFuture<PendingApplicationAction> registerInternal(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.Path path,
            PendingApplicationAction.ConnectionContext connectionContext,
            RegistrationMetadata registrationMetadata,
            Consumer<PendingApplicationAction> progressListener) {
        if (path == PendingApplicationAction.Path.UNKNOWN_PERSISTED) {
            throw new IllegalArgumentException("UNKNOWN_PERSISTED path is reserved for durable terminal recovery");
        }
        PendingApplicationAction requested = new PendingApplicationAction(
                executionId,
                correlation,
                path,
                PendingApplicationAction.State.REQUESTED,
                null,
                null,
                clock.instant(),
                connectionContext);
        // listener 随 Entry 一起构造，在 pending 可见前已安装，桌面极快 progress 不会落入空窗。
        Entry candidate = new Entry(requested, registrationMetadata, safeListener(progressListener));
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("Pending application actions is closed");
            }
            Entry existing = indexedEntry(executionId);
            if (existing != null) {
                synchronized (existing) {
                    if (existing.action.correlation().equals(correlation)
                            && existing.action.path() == path
                            && Objects.equals(existing.action.connectionContext(), connectionContext)
                            && Objects.equals(existing.registrationMetadata, registrationMetadata)) {
                        return existing.terminal;
                    }
                }
                throw new IllegalStateException("Conflicting application executionId: " + executionId);
            }
            try {
                reserveAuditAdmission(candidate);
                recordRegistered(requested, registrationMetadata);
                schedule(candidate, timeouts.acceptTimeout(),
                        PendingApplicationAction.State.REQUESTED, "accept timeout");
                synchronized (indexLock) {
                    pending.put(executionId, candidate);
                    candidate.indexed = true;
                }
            } catch (RuntimeException failure) {
                releaseAuditAdmission(candidate);
                candidate.terminal.completeExceptionally(failure);
                throw failure;
            }
            return candidate.terminal;
        }
    }

    @Override
    @PreDestroy
    public void close() {
        boolean firstClose;
        synchronized (lifecycleLock) {
            firstClose = !closed;
            closed = true;
        }
        if (firstClose) {
            onConnectionClosed("pending application actions closed");
        }
        long deadline = System.nanoTime() + timeouts.reconciliationGraceTimeout().toNanos();
        awaitLateAdmissions(deadline);
        if (lateAdmissionsPending()) {
            throw new IllegalStateException("Application action historical late admission remains after shutdown grace period");
        }
        while (completedRetentionCount() > 0 && System.nanoTime() < deadline) {
            for (Entry entry : completedEntries()) {
                drainAuditOnClose(entry, deadline);
            }
            if (completedRetentionCount() > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }
        if (ownedScheduler) {
            scheduler.shutdown();
        }
        if (completedRetentionCount() > 0) {
            log.error("Application action shutdown audit drain exceeded grace period: remaining={}",
                    completedRetentionCount());
            throw new IllegalStateException("Application action audit backlog remains after shutdown grace period");
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

    int completedRetentionCount() {
        synchronized (indexLock) {
            return completed.size();
        }
    }

    int pendingLateAuditCount() {
        synchronized (indexLock) {
            return pendingLateAudits;
        }
    }

    /** ACK 丢失不等于桌面未执行：保留原 execution，并立即进入一次状态查询和有界对账。 */
    public CompletableFuture<PendingApplicationAction> acknowledgementUncertain(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext,
            String reason) {
        Entry entry = indexedEntry(executionId);
        if (entry != null) {
            synchronized (entry) {
                IndexOwnership ownership = indexOwnership(entry, executionId);
                if (ownership != IndexOwnership.NONE
                        && (!entry.matches(correlation) || !entry.authorized(connectionContext))) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("application action acknowledgement scope mismatch"));
                }
                if (ownership == IndexOwnership.COMPLETED) {
                    return entry.terminal;
                }
                if (ownership == IndexOwnership.PENDING) {
                    entry.acknowledgementUncertain = true;
                    entry.cancelTimeout();
                    reconcileUncertain(entry, reason);
                    return entry.terminal;
                }
            }
        }
        return completedOrStoredTerminal(executionId, correlation, connectionContext)
                .orElseGet(() -> CompletableFuture.failedFuture(
                        new IllegalStateException("application action is no longer pending")));
    }

    /** A correlated negative ACK proves the request was rejected before execution uncertainty began. */
    public CompletableFuture<PendingApplicationAction> confirmedRequestRejected(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext,
            String errorCode,
            String reason) {
        TerminalPublication publication = null;
        Entry entry = indexedEntry(executionId);
        if (entry != null) {
            synchronized (entry) {
                IndexOwnership ownership = indexOwnership(entry, executionId);
                if (ownership != IndexOwnership.NONE
                        && (!entry.matches(correlation) || !entry.authorized(connectionContext))) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("application action rejection scope mismatch"));
                }
                if (ownership == IndexOwnership.COMPLETED) {
                    return entry.terminal;
                }
                if (ownership == IndexOwnership.PENDING) {
                    if (entry.action.state() != PendingApplicationAction.State.REQUESTED
                            || entry.acknowledgementUncertain) {
                        return entry.terminal;
                    }
                    ObjectNode payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                            .put("errorCode", stableRequestRejectionCode(errorCode));
                    PendingApplicationAction rejected = entry.action.toTerminal(
                            PendingApplicationAction.State.REJECTED, payload, reason, clock.instant());
                    publication = finishLocked(entry, rejected, false, false);
                }
            }
            if (publication != null) {
                publishTerminal(publication);
                return entry.terminal;
            }
        }
        return completedOrStoredTerminal(executionId, correlation, connectionContext)
                .orElseGet(() -> CompletableFuture.failedFuture(
                        new IllegalStateException("application action is no longer pending")));
    }

    private static String stableRequestRejectionCode(String errorCode) {
        return "protocol_error".equals(errorCode) ? "protocol_error" : "remote_request_failed";
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
        return terminalStore.findTerminal(executionId, correlation, connectionContext);
    }

    /** Turn 结束前先向桌面发送取消；pre-run 本地取消，RUNNING 发送失败即进入结果未知。 */
    public int cancelByTurn(String turnId) {
        int matched = 0;
        for (Entry entry : pending.values()) {
            PendingApplicationAction action;
            boolean acknowledgementUncertain;
            boolean possiblyExecuting;
            synchronized (entry) {
                if (!turnId.equals(entry.action.correlation().turnId()) || entry.action.isTerminal()) {
                    continue;
                }
                action = entry.action;
                acknowledgementUncertain = entry.acknowledgementUncertain;
                possiblyExecuting = mayHaveExecuted(entry);
                matched++;
            }
            CompletableFuture<Boolean> sent;
            try {
                sent = cancelSender.send(action);
            } catch (RuntimeException failure) {
                sent = CompletableFuture.failedFuture(failure);
            }
            if (!possiblyExecuting) {
                cancel(action.executionId(), action.correlation(), "turn canceled before execute");
                continue;
            }
            if (acknowledgementUncertain) {
                synchronized (entry) {
                    scheduleOutcomeUnknown(entry, "application action cancel confirmation timeout");
                }
            }
            if (sent == null) {
                markPossiblyExecutingCancelUnconfirmed(entry, "application action cancel send failed");
                continue;
            }
            sent.whenComplete((confirmed, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(confirmed)) {
                    markPossiblyExecutingCancelUnconfirmed(entry, "application action cancel was not confirmed");
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
            TerminalPublication publication = null;
            synchronized (entry) {
                if (pending.get(entry.action.executionId()) != entry
                        || mayHaveExecuted(entry)
                        || entry.action.isTerminal()
                        || !scope.equals(entry.action.connectionContext())) {
                    continue;
                }
                PendingApplicationAction terminal = entry.action.toTerminal(
                        PendingApplicationAction.State.EXPIRED, null, reason, clock.instant());
                publication = finishLocked(entry, terminal, false, false);
            }
            if (publication != null) {
                publishTerminal(publication);
                expired++;
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
        PendingApplicationAction previous;
        PendingApplicationAction running;
        synchronized (entry) {
            if (pending.get(executionId) != entry
                    || !entry.matches(correlation)
                    || !entry.authorized(connectionContext)
                    || !mayEnterRunning(entry.action)) {
                return false;
            }
            previous = entry.action;
            running = previous.transition(
                    PendingApplicationAction.State.RUNNING, null, clock.instant());
            ScheduledFuture<?> replacement = createTimeout(
                    entry, timeouts.executeTimeout(), PendingApplicationAction.State.RUNNING, "execute timeout");
            if (pending.get(executionId) != entry || entry.action != previous) {
                replacement.cancel(false);
                return false;
            }
            entry.action = running;
            entry.replaceTimeout(replacement);
            entry.enqueueAudit(AuditPublication.transition(previous, running));
        }
        drainAudit(entry);
        entry.notifyProgress(running);
        return true;
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
        Entry entry = indexedEntry(executionId);
        if (entry == null) {
            return handleHistoricalLate(
                    executionId, correlation, connectionContext, terminalState, payload);
        }
        TerminalPublication publication;
        PendingApplicationAction synthesizedRunning = null;
        boolean historicalFallback = false;
        boolean liveLate = false;
        synchronized (entry) {
            IndexOwnership ownership = indexOwnership(entry, executionId);
            if (ownership == IndexOwnership.NONE) {
                publication = null;
                historicalFallback = true;
            } else if (!entry.matches(correlation) || !entry.authorized(connectionContext)) {
                return false;
            } else if (ownership == IndexOwnership.COMPLETED && entry.action.isTerminal()) {
                AuditPublication latePublication;
                try {
                    latePublication = AuditPublication.terminal(
                            entry.action.toTerminal(
                                    terminalState, payload, "late desktop terminal", clock.instant()),
                            true, false);
                } catch (RuntimeException failure) {
                    log.warn("Application action live late result could not be constructed: executionId={}, reasonType={}",
                            executionId, failure.getClass().getSimpleName());
                    log.debug("Application action live late result construction failure", failure);
                    return false;
                }
                if (!reserveLateAuditAdmission()) {
                    log.error("Application action late result rejected because late audit backlog is full: executionId={}",
                            executionId);
                } else {
                    entry.enqueueAudit(latePublication);
                }
                liveLate = true;
                publication = null;
            } else if (ownership != IndexOwnership.PENDING) {
                publication = null;
                historicalFallback = true;
            } else {
                boolean synthesizeRunning = !entry.acknowledgementUncertain
                        && shouldSynthesizeRunning(entry.action, terminalState);
                if (!(entry.acknowledgementUncertain && terminalState.isTerminal())
                        && !terminalAllowed(entry.action.state(), terminalState)
                        && !synthesizeRunning) {
                    return false;
                }
                if (synthesizeRunning) {
                    PendingApplicationAction previous = entry.action;
                    synthesizedRunning = previous.transition(
                            PendingApplicationAction.State.RUNNING, null, clock.instant());
                    entry.action = synthesizedRunning;
                    entry.enqueueAudit(AuditPublication.transition(previous, synthesizedRunning));
                }
                PendingApplicationAction.State effectiveState = mayHaveExecuted(entry)
                        && terminalState == PendingApplicationAction.State.EXPIRED
                        ? PendingApplicationAction.State.OUTCOME_UNKNOWN
                        : terminalState;
                PendingApplicationAction completed = entry.action.toTerminal(
                        effectiveState,
                        payload,
                        effectiveState == terminalState ? null : "desktop expired after execution may have started",
                        clock.instant());
                publication = finishLocked(entry, completed, false,
                        effectiveState == PendingApplicationAction.State.OUTCOME_UNKNOWN);
                if (publication == null) {
                    historicalFallback = true;
                }
            }
        }
        if (historicalFallback) {
            return handleHistoricalLate(
                    executionId, correlation, connectionContext, terminalState, payload);
        }
        if (liveLate) {
            drainAudit(entry);
            return false;
        }
        if (synthesizedRunning != null) {
            entry.notifyProgress(synthesizedRunning);
        }
        publishTerminal(publication);
        return true;
    }

    public boolean cancel(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            String reason) {
        Entry entry = pending.get(executionId);
        if (entry == null) {
            return false;
        }
        TerminalPublication publication;
        synchronized (entry) {
            if (pending.get(executionId) != entry || !entry.matches(correlation)) {
                return false;
            }
            PendingApplicationAction.State terminal = mayHaveExecuted(entry)
                    ? PendingApplicationAction.State.OUTCOME_UNKNOWN
                    : PendingApplicationAction.State.CANCELED;
            PendingApplicationAction completed = entry.action.toTerminal(terminal, null, reason, clock.instant());
            publication = finishLocked(entry, completed, false,
                    terminal == PendingApplicationAction.State.OUTCOME_UNKNOWN);
        }
        if (publication == null) {
            return false;
        }
        publishTerminal(publication);
        return true;
    }

    public void onConnectionClosed(String reason) {
        onConnectionClosed(null, reason);
    }

    public void onConnectionClosed(String webSocketSessionId, String reason) {
        for (Entry entry : pending.values()) {
            TerminalPublication publication = null;
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
                boolean possiblyExecuting = mayHaveExecuted(entry);
                String terminalReason = possiblyExecuting
                        ? reason
                        : "disconnected-before-execute: " + reason;
                PendingApplicationAction completed = entry.action.toTerminal(
                        possiblyExecuting
                                ? PendingApplicationAction.State.OUTCOME_UNKNOWN
                                : PendingApplicationAction.State.CANCELED,
                        null,
                        terminalReason,
                        clock.instant());
                publication = finishLocked(entry, completed, false, possiblyExecuting);
            }
            if (publication != null) {
                publishTerminal(publication);
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
        PendingApplicationAction previous;
        PendingApplicationAction transitioned;
        synchronized (entry) {
            if (pending.get(executionId) != entry
                    || !entry.matches(correlation)
                    || !entry.authorized(connectionContext)
                    || entry.action.state() != expected) {
                return false;
            }
            previous = entry.action;
            transitioned = previous.transition(next, null, clock.instant());
            TimeoutSpec timeout = timeoutResolver.resolve(transitioned);
            ScheduledFuture<?> replacement = createTimeout(
                    entry, timeout.duration(), next, timeout.reason());
            if (pending.get(executionId) != entry || entry.action != previous) {
                replacement.cancel(false);
                return false;
            }
            entry.action = transitioned;
            entry.replaceTimeout(replacement);
            entry.enqueueAudit(AuditPublication.transition(previous, transitioned));
        }
        drainAudit(entry);
        entry.notifyProgress(transitioned);
        return true;
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
        TerminalPublication publication = null;
        boolean reconcile = false;
        synchronized (entry) {
            if (entry.action.state() != expectedState || pending.get(entry.action.executionId()) != entry) {
                return;
            }
            if (expectedState == PendingApplicationAction.State.RUNNING) {
                reconcile = true;
            } else {
                publication = finishLocked(entry, entry.action.toTerminal(
                        PendingApplicationAction.State.EXPIRED, null, reason, clock.instant()), false, false);
            }
        }
        if (reconcile) {
            reconcileRunningTimeout(entry);
        } else if (publication != null) {
            publishTerminal(publication);
        }
    }

    private void reconcileRunningTimeout(Entry entry) {
        reconcileUncertain(entry, "execute timeout");
    }

    private void reconcileUncertain(Entry entry, String reason) {
        CompletableFuture<RemoteStatus> query;
        try {
            query = statusQuery.query(entry.action);
        } catch (RuntimeException failure) {
            scheduleOutcomeUnknown(entry, reason);
            return;
        }
        if (query == null) {
            scheduleOutcomeUnknown(entry, reason);
            return;
        }
        query.whenComplete((status, failure) -> {
            TerminalPublication publication = null;
            boolean scheduleGrace = false;
            synchronized (entry) {
                if (!isCurrentReconciliation(entry)) {
                    return;
                }
                Optional<PendingApplicationAction> stored = findTerminalForStateMachine(
                        entry.action.executionId(), entry.action.correlation(), entry.action.connectionContext());
                if (stored.filter(terminal -> matchesStoredTerminal(entry.action, terminal)).isPresent()) {
                    publication = adoptStoredTerminalLocked(entry, stored.orElseThrow());
                } else if (failure == null && status != null && status.terminal() != null) {
                    PendingApplicationAction.State remote = status.terminal();
                    if (!terminalAllowedForEntry(entry, remote)) {
                        scheduleGrace = true;
                    } else {
                        PendingApplicationAction.State effective = remote == PendingApplicationAction.State.EXPIRED
                                ? PendingApplicationAction.State.OUTCOME_UNKNOWN
                                : remote;
                        publication = finishLocked(entry, entry.action.toTerminal(
                                        effective, status.payload(), "status query terminal", clock.instant()),
                                false,
                                effective == PendingApplicationAction.State.OUTCOME_UNKNOWN);
                    }
                } else {
                    scheduleGrace = true;
                }
            }
            if (publication != null) {
                publishTerminal(publication);
            } else if (scheduleGrace) {
                scheduleOutcomeUnknown(entry, reason);
            }
        });
    }

    private void markPossiblyExecutingCancelUnconfirmed(Entry entry, String reason) {
        TerminalPublication publication;
        synchronized (entry) {
            if (!isCurrentPossiblyExecuting(entry)) {
                return;
            }
            PendingApplicationAction unknown = entry.action.toTerminal(
                    PendingApplicationAction.State.OUTCOME_UNKNOWN, null, reason, clock.instant());
            publication = finishLocked(entry, unknown, false, true);
        }
        if (publication != null) {
            publishTerminal(publication);
        }
    }

    private void scheduleOutcomeUnknown(Entry entry) {
        scheduleOutcomeUnknown(entry, "reconciliation");
    }

    private void scheduleOutcomeUnknown(Entry entry, String reason) {
        if (!isCurrentReconciliation(entry)) {
            return;
        }
        ScheduledFuture<?> replacement;
        try {
            replacement = Objects.requireNonNull(scheduler.schedule(() -> {
                TerminalPublication publication = null;
                synchronized (entry) {
                    if (!isCurrentReconciliation(entry)) {
                        return;
                    }
                    Optional<PendingApplicationAction> stored = findTerminalForStateMachine(
                            entry.action.executionId(), entry.action.correlation(), entry.action.connectionContext());
                    if (stored.filter(terminal -> matchesStoredTerminal(entry.action, terminal)).isPresent()) {
                        publication = adoptStoredTerminalLocked(entry, stored.orElseThrow());
                    } else {
                        PendingApplicationAction unknown = entry.action.toTerminal(
                                PendingApplicationAction.State.OUTCOME_UNKNOWN,
                                null,
                                reason + ": reconciliation grace timeout",
                                clock.instant());
                        publication = finishLocked(entry, unknown, false, true);
                    }
                }
                if (publication != null) {
                    publishTerminal(publication);
                }
            }, timeouts.reconciliationGraceTimeout().toNanos(), TimeUnit.NANOSECONDS),
                    "scheduler returned null reconciliation grace timeout");
        } catch (RuntimeException failure) {
            TerminalPublication publication = null;
            if (isCurrentReconciliation(entry)) {
                synchronized (entry) {
                    if (isCurrentReconciliation(entry)) {
                        PendingApplicationAction unknown = entry.action.toTerminal(
                                PendingApplicationAction.State.OUTCOME_UNKNOWN,
                                null,
                                "reconciliation grace schedule failed",
                                clock.instant());
                        publication = finishLocked(entry, unknown, false, true);
                    }
                }
            }
            if (publication != null) {
                publishTerminal(publication);
            }
            return;
        }
        if (!isCurrentReconciliation(entry)) {
            replacement.cancel(false);
            return;
        }
        entry.replaceTimeout(replacement);
    }

    private TerminalPublication finishLocked(
            Entry entry,
            PendingApplicationAction terminal,
            boolean lateResult,
            boolean queueReconciliation) {
        if (!transferToCompleted(entry)) {
            return null;
        }
        entry.cancelTimeout();
        entry.action = terminal;
        entry.enqueueAudit(AuditPublication.terminal(terminal, lateResult, queueReconciliation));
        TerminalPublication publication = new TerminalPublication(
                entry, terminal, lateResult, queueReconciliation, true);
        return publication;
    }

    private void publishTerminal(TerminalPublication publication) {
        publication.entry().terminal.complete(publication.terminal());
        publication.entry().notifyProgress(publication.terminal());
        if (publication.recordTerminal()) {
            drainAudit(publication.entry());
        }
    }

    private Optional<PendingApplicationAction> findTerminalForStateMachine(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext) {
        try {
            return connectionContext == null
                    ? terminalStore.findTerminal(executionId, correlation)
                    : terminalStore.findTerminal(executionId, correlation, connectionContext);
        } catch (RuntimeException failure) {
            log.warn("Application action terminal lookup unavailable; continuing bounded state recovery: executionId={}, reasonType={}",
                    executionId, failure.getClass().getSimpleName());
            log.debug("Application action terminal lookup failure", failure);
            return Optional.empty();
        }
    }

    private void recordRegistered(
            PendingApplicationAction requested,
            RegistrationMetadata registrationMetadata) {
        if (registrationMetadata == null) {
            terminalStore.recordRegistered(requested);
        } else {
            terminalStore.recordRegistered(
                    requested,
                    registrationMetadata.actionId(),
                    registrationMetadata.actionVersion(),
                    registrationMetadata.requestFingerprint());
        }
    }

    private Entry completedEntry(String executionId) {
        synchronized (indexLock) {
            return completed.get(executionId);
        }
    }

    private Optional<CompletableFuture<PendingApplicationAction>> completedOrStoredTerminal(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext) {
        Entry completedEntry = completedEntry(executionId);
        if (completedEntry != null) {
            synchronized (completedEntry) {
                if (indexOwnership(completedEntry, executionId) == IndexOwnership.COMPLETED
                        && completedEntry.matches(correlation)
                        && completedEntry.authorized(connectionContext)) {
                    return Optional.of(completedEntry.terminal);
                }
            }
        }
        return findTerminalForStateMachine(executionId, correlation, connectionContext)
                .map(CompletableFuture::completedFuture);
    }

    private Entry indexedEntry(String executionId) {
        synchronized (indexLock) {
            Entry entry = pending.get(executionId);
            return entry == null ? completed.get(executionId) : entry;
        }
    }

    /** 在持有 Entry 监视器时复核索引所有权，避免使用已经退役的旧引用。 */
    private IndexOwnership indexOwnership(Entry entry, String executionId) {
        synchronized (indexLock) {
            if (!entry.indexed) {
                return IndexOwnership.NONE;
            }
            if (pending.get(executionId) == entry) {
                return IndexOwnership.PENDING;
            }
            return completed.get(executionId) == entry
                    ? IndexOwnership.COMPLETED
                    : IndexOwnership.NONE;
        }
    }

    /** 统一处理内存索引缺失或旧 Entry 退役后的历史 late result。 */
    private boolean handleHistoricalLate(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext,
            PendingApplicationAction.State terminalState,
            JsonNode payload) {
        if (!beginLateAdmission()) {
            log.error("Application action historical late result rejected after shutdown: executionId={}", executionId);
            return false;
        }
        try {
            PendingApplicationAction storedTerminal = findTerminalForStateMachine(
                            executionId, correlation, connectionContext)
                    .filter(stored -> connectionContext == null
                            || connectionContext.equals(stored.connectionContext()))
                    .orElse(null);
            if (storedTerminal == null) {
                return false;
            }
            AuditPublication latePublication;
            try {
                latePublication = AuditPublication.terminal(
                        storedTerminal.toTerminal(
                                terminalState, payload, "late desktop terminal", clock.instant()),
                        true, false);
            } catch (RuntimeException failure) {
                log.warn("Application action historical late result could not be constructed: executionId={}, reasonType={}",
                        executionId, failure.getClass().getSimpleName());
                log.debug("Application action historical late result construction failure", failure);
                return false;
            }
            Entry reserved = reserveCompletedForLate(storedTerminal);
            if (reserved == null) {
                log.error("Application action historical late result rejected because audit backlog is full: executionId={}",
                        executionId);
                return false;
            }
            if (!reserveLateAuditAdmission()) {
                cancelReservedLate(reserved);
                log.error("Application action historical late result rejected because late audit backlog is full: executionId={}",
                        executionId);
                drainAudit(reserved);
                return false;
            }
            try {
                enqueueReservedLate(reserved, latePublication);
            } catch (RuntimeException failure) {
                releaseLateAuditAdmission();
                cancelReservedLate(reserved);
                throw failure;
            }
            drainAudit(reserved);
            return false;
        } finally {
            endLateAdmission();
        }
    }

    private boolean beginLateAdmission() {
        synchronized (lifecycleLock) {
            if (closed) {
                return false;
            }
            inFlightLateAdmissions++;
            return true;
        }
    }

    private void endLateAdmission() {
        synchronized (lifecycleLock) {
            inFlightLateAdmissions--;
            lifecycleLock.notifyAll();
        }
    }

    private void awaitLateAdmissions(long deadline) {
        synchronized (lifecycleLock) {
            while (inFlightLateAdmissions > 0 && System.nanoTime() < deadline) {
                long remainingNanos = deadline - System.nanoTime();
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycleLock, Math.max(1L, remainingNanos));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for historical late admissions", interrupted);
                }
            }
        }
    }

    private boolean lateAdmissionsPending() {
        synchronized (lifecycleLock) {
            return inFlightLateAdmissions > 0;
        }
    }

    private List<Entry> completedEntries() {
        synchronized (indexLock) {
            return List.copyOf(completed.values());
        }
    }

    /** 为历史 late 入队预留 Entry，退役逻辑会等待预留完成后再释放容量。 */
    private Entry reserveCompletedForLate(PendingApplicationAction storedTerminal) {
        String executionId = storedTerminal.executionId();
        while (true) {
            Entry existing = indexedEntry(executionId);
            if (existing != null) {
                synchronized (existing) {
                    if (indexOwnership(existing, executionId) != IndexOwnership.NONE) {
                        existing.lateEnqueueReservations++;
                        return existing;
                    }
                }
                continue;
            }
            Entry candidate = new Entry(storedTerminal, null, ignored -> { });
            synchronized (candidate) {
                synchronized (indexLock) {
                    if (pending.containsKey(executionId) || completed.containsKey(executionId)) {
                        continue;
                    }
                    if (activeAdmissions >= auditBacklogCapacity) {
                        return null;
                    }
                    activeAdmissions++;
                    candidate.admissionHeld = true;
                    candidate.indexed = true;
                    candidate.lateEnqueueReservations++;
                    completed.put(executionId, candidate);
                    return candidate;
                }
            }
        }
    }

    /** 消费历史 late 入队预留；队列写入与预留释放在同一 Entry 临界区完成。 */
    private void enqueueReservedLate(Entry entry, AuditPublication publication) {
        synchronized (entry) {
            if (entry.lateEnqueueReservations <= 0) {
                throw new IllegalStateException("Application action late enqueue reservation is missing");
            }
            entry.enqueueAudit(publication);
            entry.lateEnqueueReservations--;
            entry.notifyAll();
        }
    }

    private void cancelReservedLate(Entry entry) {
        synchronized (entry) {
            if (entry.lateEnqueueReservations > 0) {
                entry.lateEnqueueReservations--;
                entry.notifyAll();
            }
        }
    }

    /** late audit 使用独立的 publication 级容量，防止单个 execution 无限堆积。 */
    private boolean reserveLateAuditAdmission() {
        synchronized (indexLock) {
            if (pendingLateAudits >= auditBacklogCapacity) {
                return false;
            }
            pendingLateAudits++;
            return true;
        }
    }

    private void releaseLateAuditAdmission() {
        synchronized (indexLock) {
            if (pendingLateAudits <= 0) {
                throw new IllegalStateException("Application action late audit admission underflow");
            }
            pendingLateAudits--;
        }
    }

    private boolean transferToCompleted(Entry entry) {
        synchronized (indexLock) {
            String executionId = entry.action.executionId();
            if (!pending.remove(executionId, entry)) {
                return false;
            }
            completed.put(executionId, entry);
            entry.indexed = true;
            return true;
        }
    }

    private void reserveAuditAdmission(Entry entry) {
        synchronized (indexLock) {
            if (activeAdmissions >= auditBacklogCapacity) {
                throw new IllegalStateException("Application action audit backlog capacity reached");
            }
            activeAdmissions++;
            entry.admissionHeld = true;
        }
    }

    private void releaseAuditAdmission(Entry entry) {
        synchronized (indexLock) {
            if (entry.admissionHeld) {
                entry.admissionHeld = false;
                activeAdmissions--;
            }
        }
    }

    private void retireCompleted(Entry entry) {
        synchronized (indexLock) {
            if (completed.remove(entry.action.executionId(), entry)) {
                entry.indexed = false;
                if (entry.admissionHeld) {
                    entry.admissionHeld = false;
                    activeAdmissions--;
                }
            }
        }
    }

    private void drainAudit(Entry entry) {
        synchronized (entry) {
            if (entry.auditDraining || entry.auditRetryScheduled) {
                return;
            }
            entry.auditDraining = true;
        }
        while (true) {
            AuditPublication publication;
            synchronized (entry) {
                publication = entry.auditQueue.peekFirst();
                if (publication == null) {
                    entry.auditDraining = false;
                    entry.notifyAll();
                    if (entry.action.isTerminal() && entry.lateEnqueueReservations == 0) {
                        retireCompleted(entry);
                    }
                    return;
                }
            }
            try {
                publication.publish(terminalStore);
            } catch (RuntimeException failure) {
                scheduleAuditRetry(entry, publication, failure);
                return;
            }
            synchronized (entry) {
                if (entry.auditQueue.peekFirst() == publication) {
                    entry.auditQueue.removeFirst();
                    if (publication.lateResult) {
                        releaseLateAuditAdmission();
                    }
                }
            }
        }
    }

    private void drainAuditOnClose(Entry entry, long deadline) {
        synchronized (entry) {
            if (entry.auditRetry != null) {
                entry.auditRetry.cancel(false);
                entry.auditRetry = null;
            }
            entry.auditRetryScheduled = false;
            while (entry.auditDraining && System.nanoTime() < deadline) {
                long remainingNanos = deadline - System.nanoTime();
                try {
                    TimeUnit.NANOSECONDS.timedWait(entry, Math.max(1L, remainingNanos));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while draining application action audit", interrupted);
                }
            }
        }
        if (System.nanoTime() < deadline) {
            drainAudit(entry);
        }
    }

    private void scheduleAuditRetry(Entry entry, AuditPublication publication, RuntimeException failure) {
        int attempt;
        synchronized (entry) {
            attempt = ++publication.attempts;
            entry.auditDraining = false;
            entry.notifyAll();
            entry.auditRetryScheduled = true;
        }
        log.warn("Application action audit publication failed: executionId={}, attempt={}, reasonType={}",
                publication.action.executionId(), attempt, failure.getClass().getSimpleName());
        log.debug("Application action audit publication failure", failure);
        if (closed) {
            synchronized (entry) {
                entry.auditRetryScheduled = false;
            }
            return;
        }
        try {
            ScheduledFuture<?> retry = scheduler.schedule(() -> {
                synchronized (entry) {
                    entry.auditRetry = null;
                    entry.auditRetryScheduled = false;
                }
                if (!closed) {
                    drainAudit(entry);
                }
            }, auditRetryDelayMillis(attempt), TimeUnit.MILLISECONDS);
            synchronized (entry) {
                if (closed || !entry.auditRetryScheduled) {
                    retry.cancel(false);
                } else {
                    entry.auditRetry = retry;
                }
            }
        } catch (RuntimeException schedulingFailure) {
            synchronized (entry) {
                entry.auditRetryScheduled = false;
                entry.auditRetry = null;
            }
            log.warn("Application action audit retry scheduling failed: executionId={}, reasonType={}",
                    publication.action.executionId(), schedulingFailure.getClass().getSimpleName());
        }
    }

    private static long auditRetryDelayMillis(int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 10);
        return Math.min(AUDIT_RETRY_BASE_MILLIS << shift, AUDIT_RETRY_MAX_MILLIS);
    }

    private TerminalPublication adoptStoredTerminalLocked(Entry entry, PendingApplicationAction terminal) {
        if (!matchesStoredTerminal(entry.action, terminal)) {
            return null;
        }
        if (!transferToCompleted(entry)) {
            return null;
        }
        entry.cancelTimeout();
        entry.action = terminal;
        return new TerminalPublication(entry, terminal, false, false, false);
    }

    private static boolean matchesStoredTerminal(
            PendingApplicationAction current,
            PendingApplicationAction stored) {
        return current.connectionContext() != null && matchesTerminalIdentity(current, stored);
    }

    private static boolean matchesTerminalIdentity(
            PendingApplicationAction current,
            PendingApplicationAction stored) {
        return stored != null
                && stored.isTerminal()
                && current.executionId().equals(stored.executionId())
                && current.correlation().equals(stored.correlation())
                && (stored.path() == PendingApplicationAction.Path.UNKNOWN_PERSISTED
                        || current.path() == stored.path())
                && Objects.equals(current.connectionContext(), stored.connectionContext());
    }

    private boolean isCurrentPossiblyExecuting(Entry entry) {
        return pending.get(entry.action.executionId()) == entry && mayHaveExecuted(entry);
    }

    private boolean isCurrentReconciliation(Entry entry) {
        return pending.get(entry.action.executionId()) == entry
                && mayHaveExecuted(entry);
    }

    private static boolean mayHaveExecuted(Entry entry) {
        return entry.action.state() == PendingApplicationAction.State.RUNNING
                || entry.acknowledgementUncertain;
    }

    private static boolean terminalAllowedForEntry(Entry entry, PendingApplicationAction.State terminal) {
        return entry.acknowledgementUncertain
                ? terminal != null && terminal.isTerminal()
                : terminalAllowed(entry.action.state(), terminal);
    }

    private static boolean mayEnterRunning(PendingApplicationAction action) {
        return switch (action.path()) {
            case READ_ONLY -> action.state() == PendingApplicationAction.State.ACCEPTED;
            case REVERSIBLE_WRITE -> action.state() == PendingApplicationAction.State.PREVIEWED;
            case HIGH_RISK -> action.state() == PendingApplicationAction.State.APPROVAL_REQUIRED;
            case UNKNOWN_PERSISTED -> false;
        };
    }

    /** 快速动作允许省略 wire 进度，但只能从该风险路径的合法执行门槛补记 RUNNING。 */
    private static boolean shouldSynthesizeRunning(
            PendingApplicationAction action,
            PendingApplicationAction.State terminal) {
        return (terminal == PendingApplicationAction.State.COMPLETED
                || terminal == PendingApplicationAction.State.OUTCOME_UNKNOWN)
                && mayEnterRunning(action);
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

    private static Consumer<PendingApplicationAction> safeListener(
            Consumer<PendingApplicationAction> listener) {
        return listener == null ? ignored -> { } : listener;
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

    private record TerminalPublication(
            Entry entry,
            PendingApplicationAction terminal,
            boolean lateResult,
            boolean queueReconciliation,
            boolean recordTerminal) {
    }

    private static final class AuditPublication {
        private final PendingApplicationAction previous;
        private final PendingApplicationAction action;
        private final boolean terminal;
        private final boolean lateResult;
        private final boolean queueReconciliation;
        private int attempts;
        private boolean recordPublished;

        private AuditPublication(
                PendingApplicationAction previous,
                PendingApplicationAction action,
                boolean terminal,
                boolean lateResult,
                boolean queueReconciliation) {
            this.previous = previous;
            this.action = action;
            this.terminal = terminal;
            this.lateResult = lateResult;
            this.queueReconciliation = queueReconciliation;
        }

        private static AuditPublication transition(
                PendingApplicationAction previous,
                PendingApplicationAction current) {
            return new AuditPublication(previous, current, false, false, false);
        }

        private static AuditPublication terminal(
                PendingApplicationAction terminal,
                boolean lateResult,
                boolean queueReconciliation) {
            return new AuditPublication(null, terminal, true, lateResult, queueReconciliation);
        }

        private void publish(ApplicationActionTerminalStore store) {
            if (!recordPublished) {
                if (terminal) {
                    store.recordTerminal(action, lateResult);
                } else {
                    store.recordTransition(previous, action, false);
                }
                recordPublished = true;
            }
            if (queueReconciliation) store.queueReconciliation(action);
        }
    }

    /** Task 28 前的进程内 fallback；容量受限且不承诺重启持久化。 */
    private static final class BoundedVolatileTerminalStore implements ApplicationActionTerminalStore {
        private static final int CAPACITY = 1_024;
        private final LinkedHashMap<String, PendingApplicationAction> firstTerminals = new LinkedHashMap<>();

        @Override
        public void recordRegistered(PendingApplicationAction requested) {
            // Volatile fallback only guarantees first terminal recovery.
        }

        @Override
        public void recordTransition(
                PendingApplicationAction previous,
                PendingApplicationAction current,
                boolean lateResult) {
            // Volatile fallback only guarantees first terminal recovery.
        }

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
        private final RegistrationMetadata registrationMetadata;
        private Consumer<PendingApplicationAction> progressListener = ignored -> { };
        private final CompletableFuture<PendingApplicationAction> terminal = new CompletableFuture<>();
        private ScheduledFuture<?> timeout;
        private boolean acknowledgementUncertain;
        private final ArrayDeque<AuditPublication> auditQueue = new ArrayDeque<>();
        private boolean auditDraining;
        private boolean auditRetryScheduled;
        private ScheduledFuture<?> auditRetry;
        private boolean admissionHeld;
        private boolean indexed;
        /** 历史 late result 已取得容量但尚未完成入队的数量。 */
        private int lateEnqueueReservations;

        private Entry(
                PendingApplicationAction action,
                RegistrationMetadata registrationMetadata,
                Consumer<PendingApplicationAction> progressListener) {
            this.action = action;
            this.registrationMetadata = registrationMetadata;
            this.progressListener = progressListener;
        }

        private void enqueueAudit(AuditPublication publication) {
            auditQueue.addLast(publication);
        }

        private void notifyProgress(PendingApplicationAction snapshot) {
            try {
                progressListener.accept(snapshot);
            } catch (RuntimeException ignored) {
                // UI 进度属于观测层，发送失败不能反向破坏动作状态机。
            }
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

    private enum IndexOwnership {
        PENDING,
        COMPLETED,
        NONE
    }

    /** 只保存目录身份和不可逆摘要，不携带原始动作输入。 */
    public record RegistrationMetadata(
            String actionId,
            int actionVersion,
            String requestFingerprint) {
        public RegistrationMetadata {
            if (actionId == null || actionId.isBlank()) {
                throw new IllegalArgumentException("actionId must not be blank");
            }
            if (actionVersion <= 0) {
                throw new IllegalArgumentException("actionVersion must be positive");
            }
            if (requestFingerprint == null || requestFingerprint.isBlank()) {
                throw new IllegalArgumentException("requestFingerprint must not be blank");
            }
        }
    }
}
