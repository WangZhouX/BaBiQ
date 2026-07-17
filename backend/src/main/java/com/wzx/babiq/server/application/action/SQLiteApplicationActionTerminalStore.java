package com.wzx.babiq.server.application.action;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.persistence.entity.ApplicationActionEntity;
import com.wzx.babiq.server.persistence.entity.ApplicationActionEventEntity;
import com.wzx.babiq.server.persistence.mapper.ApplicationActionEventMapper;
import com.wzx.babiq.server.persistence.mapper.ApplicationActionMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/** SQLite 动作当前态与 append-only 事件审计实现。 */
@Component
public class SQLiteApplicationActionTerminalStore implements ApplicationActionTerminalStore {

    private static final int SUMMARY_LIMIT = 512;
    private static final int WRITE_RETRY_LIMIT = 32;
    private final ApplicationActionMapper actions;
    private final ApplicationActionEventMapper events;
    private final ApplicationActionRedactor redactor;
    private final TransactionTemplate transactions;

    public SQLiteApplicationActionTerminalStore(
            ApplicationActionMapper actions,
            ApplicationActionEventMapper events,
            ApplicationActionRedactor redactor,
            PlatformTransactionManager transactionManager) {
        this.actions = actions;
        this.events = events;
        this.redactor = redactor;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void recordRegistered(
            PendingApplicationAction requested,
            String actionId,
            int actionVersion,
            String requestFingerprint) {
        inWriteTransaction(() -> recordRegisteredInternal(requested, actionId, actionVersion, requestFingerprint));
    }

    private void recordRegisteredInternal(
            PendingApplicationAction requested,
            String actionId,
            int actionVersion,
            String requestFingerprint) {
        requirePersistable(requested);
        if (requested.state() != PendingApplicationAction.State.REQUESTED) {
            throw new IllegalArgumentException("registered action must be REQUESTED");
        }
        ApplicationActionEntity existing = actions.selectById(requested.executionId());
        if (existing != null) {
            requireSameIdentity(existing, requested);
            requireSameRequestIdentity(existing, actionId, actionVersion, requestFingerprint);
            return;
        }
        ApplicationActionEntity entity = new ApplicationActionEntity();
        entity.setExecutionId(requested.executionId());
        entity.setActionId(requireText(actionId, "actionId"));
        entity.setActionVersion(actionVersion);
        entity.setRequestFingerprint(requireText(requestFingerprint, "requestFingerprint"));
        copyCorrelationAndScope(entity, requested);
        entity.setStatus(dbStatus(requested.state()));
        entity.setCreatedAt(requested.updatedAt().toString());
        entity.setUpdatedAt(requested.updatedAt().toString());
        actions.insert(entity);
        appendEvent(requested.executionId(), "registered", null, requested, false);
    }

    @Override
    public void recordRegistered(PendingApplicationAction requested) {
        String actionId = "application_action";
        String fingerprint = "execution:" + requested.executionId();
        recordRegistered(requested, actionId, 1, fingerprint);
    }

    @Override
    public void recordTransition(
            PendingApplicationAction previous,
            PendingApplicationAction current,
            boolean lateResult) {
        inWriteTransaction(() -> recordTransitionInternal(previous, current, lateResult));
    }

    private void recordTransitionInternal(
            PendingApplicationAction previous,
            PendingApplicationAction current,
            boolean lateResult) {
        requirePersistable(current);
        ApplicationActionEntity entity = requireCurrent(current);
        String currentDbStatus = entity.getStatus();
        if (!lateResult && isTerminalStatus(currentDbStatus)) {
            appendEvent(current.executionId(), "late_result", currentDbStatus, current, true);
            return;
        }
        if (!lateResult) {
            String expectedStatus = previous == null ? currentDbStatus : dbStatus(previous.state());
            if (!currentDbStatus.equals(expectedStatus)) {
                throw new IllegalStateException("application action transition order conflict");
            }
            entity.setStatus(dbStatus(current.state()));
            applySummary(entity, current);
            entity.setUpdatedAt(current.updatedAt().toString());
            if (current.isTerminal()) {
                entity.setTerminalAt(current.updatedAt().toString());
            }
            if (actions.updateStateIfCurrent(current.executionId(), currentDbStatus, entity) != 1) {
                ApplicationActionEntity winner = requireCurrent(current);
                if (isTerminalStatus(winner.getStatus()) && current.isTerminal()) {
                    appendEvent(current.executionId(), "late_result", winner.getStatus(), current, true);
                    return;
                }
                throw new IllegalStateException("application action transition order conflict");
            }
        }
        appendEvent(current.executionId(), lateResult ? "late_result" : "transition",
                previous == null ? currentDbStatus : dbStatus(previous.state()), current, lateResult);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PendingApplicationAction> findTerminal(
            String executionId,
            PendingApplicationAction.Correlation correlation) {
        ApplicationActionEntity entity = actions.selectById(executionId);
        // V23 scoped rows must be read with the explicit trusted scope overload.
        return matchesCorrelation(entity, correlation) && isTerminalStatus(entity.getStatus())
                && entity.getDesktopInstanceId() == null ? Optional.of(toLegacyPending(entity)) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<PendingApplicationAction> findTerminal(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext scope) {
        ApplicationActionEntity entity = actions.selectById(executionId);
        return matchesCorrelation(entity, correlation) && isTerminalStatus(entity.getStatus())
                && matchesScope(entity, scope) ? Optional.of(toPending(entity, scope)) : Optional.empty();
    }

    @Override
    public void recordTerminal(PendingApplicationAction terminal, boolean lateResult) {
        inWriteTransaction(() -> {
            ApplicationActionEntity entity = requireCurrent(terminal);
            PendingApplicationAction previous = toPending(entity, terminal.connectionContext());
            recordTransitionInternal(previous, terminal, lateResult || isTerminalStatus(entity.getStatus()));
        });
    }

    @Override
    public void queueReconciliation(PendingApplicationAction terminal) {
        // OUTCOME_UNKNOWN 当前态本身就是持久化对账队列；后续查询按 scope/status 领取。
    }

    @Transactional(readOnly = true)
    public List<ApplicationActionEntity> findByScope(
            PendingApplicationAction.ConnectionContext scope,
            List<String> statuses) {
        if (scope == null || statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return actions.selectList(Wrappers.<ApplicationActionEntity>lambdaQuery()
                .eq(ApplicationActionEntity::getDesktopInstanceId, scope.desktopInstanceId())
                .eq(ApplicationActionEntity::getDesktopSessionId, scope.desktopSessionId())
                .eq(ApplicationActionEntity::getAuthSessionId, scope.authSessionId())
                .eq(ApplicationActionEntity::getIdentityEpoch, scope.identityEpoch())
                .eq(ApplicationActionEntity::getUserId, scope.userId())
                .eq(ApplicationActionEntity::getTenantId, scope.tenantId())
                .eq(ApplicationActionEntity::getPlatformId, scope.platformId())
                .in(ApplicationActionEntity::getStatus, statuses)
                .orderByAsc(ApplicationActionEntity::getCreatedAt));
    }

    @Transactional(readOnly = true)
    public List<ApplicationActionEntity> findByStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return List.of();
        return actions.selectList(Wrappers.<ApplicationActionEntity>lambdaQuery()
                .in(ApplicationActionEntity::getStatus, statuses)
                .orderByAsc(ApplicationActionEntity::getCreatedAt));
    }

    public void recover(
            ApplicationActionEntity entity,
            String eventType,
            PendingApplicationAction.State terminalState,
            String safeReason,
            Instant occurredAt) {
        inWriteTransaction(() -> recoverInternal(entity, eventType, terminalState, safeReason, occurredAt));
    }

    private void recoverInternal(
            ApplicationActionEntity entity,
            String eventType,
            PendingApplicationAction.State terminalState,
            String safeReason,
            Instant occurredAt) {
        ApplicationActionEntity current = actions.selectById(entity.getExecutionId());
        if (current == null || isTerminalStatus(current.getStatus())) return;
        PendingApplicationAction.ConnectionContext scope = persistedScope(current);
        PendingApplicationAction previous = toPending(current, scope);
        PendingApplicationAction terminal = previous.toTerminal(terminalState, null, safeReason, occurredAt);
        current.setStatus(dbStatus(terminalState));
        current.setErrorCode(terminalState == PendingApplicationAction.State.OUTCOME_UNKNOWN
                ? "outcome_unknown" : terminalState.name().toLowerCase(Locale.ROOT));
        current.setErrorMessageRedacted(safeText(safeReason));
        current.setUpdatedAt(occurredAt.toString());
        current.setTerminalAt(occurredAt.toString());
        if (actions.updateStateIfCurrent(current.getExecutionId(), dbStatus(previous.state()), current) != 1) {
            return;
        }
        appendEvent(current.getExecutionId(), eventType, dbStatus(previous.state()), terminal, false);
    }

    /** 实例锁覆盖 TransactionTemplate commit，避免事务在 synchronized 方法返回后才提交。 */
    private void inWriteTransaction(Runnable work) {
        for (int attempt = 1; ; attempt++) {
            try {
                synchronized (this) {
                    transactions.executeWithoutResult(ignored -> work.run());
                }
                return;
            } catch (RuntimeException failure) {
                if (attempt >= WRITE_RETRY_LIMIT || !isRetryableWriteConflict(failure)) {
                    throw failure;
                }
                LockSupport.parkNanos(Math.min(attempt, 10) * 1_000_000L);
            }
        }
    }

    private static boolean isRetryableWriteConflict(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("SQLITE_BUSY")
                    || message.contains("database is locked")
                    || message.contains("bq_application_action_events.execution_id, "
                            + "bq_application_action_events.event_sequence"))) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<ApplicationActionEventEntity> events(
            String executionId,
            PendingApplicationAction.ConnectionContext scope) {
        ApplicationActionEntity entity = actions.selectById(executionId);
        if (!matchesScope(entity, scope)) {
            return List.of();
        }
        return events.selectList(Wrappers.<ApplicationActionEventEntity>lambdaQuery()
                .eq(ApplicationActionEventEntity::getExecutionId, executionId)
                .orderByAsc(ApplicationActionEventEntity::getEventSequence));
    }

    private ApplicationActionEntity requireCurrent(PendingApplicationAction action) {
        ApplicationActionEntity entity = actions.selectById(action.executionId());
        if (entity == null) {
            throw new IllegalStateException("application action is not registered");
        }
        requireSameIdentity(entity, action);
        return entity;
    }

    private void appendEvent(
            String executionId,
            String eventType,
            String fromStatus,
            PendingApplicationAction current,
            boolean lateResult) {
        List<Object> sequenceValues = events.selectObjs(Wrappers.<ApplicationActionEventEntity>query()
                .select("MAX(event_sequence)").eq("execution_id", executionId));
        Object maxValue = sequenceValues.isEmpty() ? null : sequenceValues.getFirst();
        long max = maxValue instanceof Number number ? number.longValue() : 0L;
        ApplicationActionEventEntity event = new ApplicationActionEventEntity();
        event.setEventId("action-event-" + UUID.randomUUID());
        event.setExecutionId(executionId);
        event.setEventSequence(max + 1);
        event.setEventType(eventType);
        event.setFromStatus(fromStatus);
        event.setToStatus(dbStatus(current.state()));
        event.setPayloadSummaryRedacted(summary(current));
        event.setLateResult(lateResult);
        event.setOccurredAt(current.updatedAt().toString());
        events.insert(event);
    }

    private void applySummary(ApplicationActionEntity entity, PendingApplicationAction action) {
        JsonNode payload = action.payload();
        entity.setResultSummaryRedacted(safeText(text(payload, "previewSummary")));
        entity.setErrorCode(safeIdentifier(text(payload, "errorCode")));
        entity.setErrorMessageRedacted(safeText(text(payload, "errorSummary")));
    }

    private String summary(PendingApplicationAction action) {
        JsonNode payload = action.payload();
        String value = firstNonBlank(text(payload, "previewSummary"), text(payload, "errorSummary"));
        return safeText(value);
    }

    private String safeText(String value) {
        if (value == null || value.isBlank()) return null;
        return redactor.sanitize(value, SUMMARY_LIMIT);
    }

    private static String safeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{1,128}") ? value : null;
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        return value != null && value.isValueNode() ? value.asText() : null;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static boolean matchesCorrelation(
            ApplicationActionEntity entity,
            PendingApplicationAction.Correlation correlation) {
        return entity != null && correlation != null
                && correlation.threadId().equals(entity.getThreadId())
                && correlation.turnId().equals(entity.getTurnId())
                && correlation.toolCallId().equals(entity.getToolCallId());
    }

    private static boolean matchesScope(
            ApplicationActionEntity entity,
            PendingApplicationAction.ConnectionContext scope) {
        return entity != null && scope != null
                && scope.desktopInstanceId().equals(entity.getDesktopInstanceId())
                && scope.desktopSessionId().equals(entity.getDesktopSessionId())
                && scope.authSessionId().equals(entity.getAuthSessionId())
                && scope.identityEpoch() == entity.getIdentityEpoch()
                && scope.userId().equals(entity.getUserId())
                && scope.tenantId().equals(entity.getTenantId())
                && scope.platformId().equals(entity.getPlatformId());
    }

    private static void requireSameIdentity(ApplicationActionEntity entity, PendingApplicationAction action) {
        if (!matchesCorrelation(entity, action.correlation())
                || !matchesScope(entity, action.connectionContext())) {
            throw new IllegalStateException("application action identity or correlation mismatch");
        }
    }

    private static void requireSameRequestIdentity(
            ApplicationActionEntity entity,
            String actionId,
            int actionVersion,
            String requestFingerprint) {
        if (!java.util.Objects.equals(entity.getActionId(), actionId)
                || !java.util.Objects.equals(entity.getActionVersion(), actionVersion)
                || !java.util.Objects.equals(entity.getRequestFingerprint(), requestFingerprint)) {
            throw new IllegalStateException("application action request identity conflict");
        }
    }

    private static void copyCorrelationAndScope(
            ApplicationActionEntity entity,
            PendingApplicationAction action) {
        PendingApplicationAction.Correlation correlation = action.correlation();
        PendingApplicationAction.ConnectionContext scope = action.connectionContext();
        entity.setThreadId(correlation.threadId());
        entity.setTurnId(correlation.turnId());
        entity.setToolCallId(correlation.toolCallId());
        entity.setDesktopInstanceId(scope.desktopInstanceId());
        entity.setDesktopSessionId(scope.desktopSessionId());
        entity.setAuthSessionId(scope.authSessionId());
        entity.setIdentityEpoch(scope.identityEpoch());
        entity.setUserId(scope.userId());
        entity.setTenantId(scope.tenantId());
        entity.setPlatformId(scope.platformId());
    }

    private static PendingApplicationAction.ConnectionContext persistedScope(ApplicationActionEntity entity) {
        return new PendingApplicationAction.ConnectionContext(
                "persisted", "persisted", entity.getDesktopInstanceId(), entity.getDesktopSessionId(),
                entity.getAuthSessionId(), entity.getIdentityEpoch(), entity.getUserId(), entity.getTenantId(),
                entity.getPlatformId());
    }

    private static PendingApplicationAction toPending(
            ApplicationActionEntity entity,
            PendingApplicationAction.ConnectionContext scope) {
        PendingApplicationAction.Correlation correlation = new PendingApplicationAction.Correlation(
                entity.getThreadId(), entity.getTurnId(), entity.getToolCallId());
        return new PendingApplicationAction(entity.getExecutionId(), correlation,
                PendingApplicationAction.Path.UNKNOWN_PERSISTED, domainState(entity.getStatus()), null,
                null, Instant.parse(entity.getUpdatedAt()), scope);
    }

    private static PendingApplicationAction toLegacyPending(ApplicationActionEntity entity) {
        PendingApplicationAction.Correlation correlation = new PendingApplicationAction.Correlation(
                entity.getThreadId(), entity.getTurnId(), entity.getToolCallId());
        return new PendingApplicationAction(entity.getExecutionId(), correlation,
                PendingApplicationAction.Path.UNKNOWN_PERSISTED, domainState(entity.getStatus()), null,
                null, Instant.parse(entity.getUpdatedAt()), null);
    }

    private static PendingApplicationAction withScope(
            PendingApplicationAction action,
            PendingApplicationAction.ConnectionContext scope) {
        return new PendingApplicationAction(action.executionId(), action.correlation(), action.path(),
                action.state(), action.payload(), action.reason(), action.updatedAt(), scope);
    }

    private static String dbStatus(PendingApplicationAction.State state) {
        return state == PendingApplicationAction.State.RUNNING ? "EXECUTING" : state.name();
    }

    private static PendingApplicationAction.State domainState(String status) {
        return "EXECUTING".equals(status) ? PendingApplicationAction.State.RUNNING
                : PendingApplicationAction.State.valueOf(status.toUpperCase(Locale.ROOT));
    }

    private static boolean isTerminalStatus(String status) {
        return switch (status) {
            case "COMPLETED", "FAILED", "REJECTED", "CANCELED", "EXPIRED", "OUTCOME_UNKNOWN" -> true;
            default -> false;
        };
    }

    private static void requirePersistable(PendingApplicationAction action) {
        if (action == null || action.connectionContext() == null) {
            throw new IllegalArgumentException("application action requires frozen business identity");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
