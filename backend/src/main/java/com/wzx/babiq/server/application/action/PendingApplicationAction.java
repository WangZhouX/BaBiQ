package com.wzx.babiq.server.application.action;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/** 单个桌面动作在 Agent 侧的不可变生命周期快照。 */
public record PendingApplicationAction(
        String executionId,
        Correlation correlation,
        Path path,
        State state,
        JsonNode payload,
        String reason,
        Instant updatedAt,
        ConnectionContext connectionContext
) {

    public PendingApplicationAction {
        requireText(executionId, "executionId");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(updatedAt, "updatedAt");
        payload = payload == null ? null : payload.deepCopy();
    }

    public PendingApplicationAction(
            String executionId,
            Correlation correlation,
            Path path,
            State state,
            JsonNode payload,
            String reason,
            Instant updatedAt) {
        this(executionId, correlation, path, state, payload, reason, updatedAt, null);
    }

    @Override
    public JsonNode payload() {
        return payload == null ? null : payload.deepCopy();
    }

    public PendingApplicationAction transition(State nextState, String nextReason, Instant now) {
        return new PendingApplicationAction(
                executionId, correlation, path, nextState, payload, nextReason, now, connectionContext);
    }

    public PendingApplicationAction toTerminal(
            State terminalState,
            JsonNode terminalPayload,
            String terminalReason,
            Instant now) {
        if (!terminalState.isTerminal()) {
            throw new IllegalArgumentException("state is not terminal");
        }
        return new PendingApplicationAction(
                executionId, correlation, path, terminalState, terminalPayload, terminalReason, now, connectionContext);
    }

    public PendingApplicationAction withConnectionContext(ConnectionContext context) {
        return new PendingApplicationAction(
                executionId, correlation, path, state, payload, reason, updatedAt,
                Objects.requireNonNull(context, "context"));
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    @Override
    public String toString() {
        return "PendingApplicationAction(executionId=" + executionId
                + ", correlation=" + correlation
                + ", path=" + path
                + ", state=" + state
                + ", payload=[REDACTED], reason=[REDACTED], updatedAt=" + updatedAt
                + ", connectionContext=" + (connectionContext == null ? "null" : connectionContext) + ")";
    }

    public enum Path {
        READ_ONLY,
        REVERSIBLE_WRITE,
        HIGH_RISK,
        /** 仅表示旧表未持久化 path；禁止注册或参与正常状态迁移。 */
        UNKNOWN_PERSISTED
    }

    public enum State {
        REQUESTED,
        ACCEPTED,
        PREVIEWED,
        APPROVAL_REQUIRED,
        RUNNING,
        COMPLETED,
        FAILED,
        REJECTED,
        CANCELED,
        EXPIRED,
        OUTCOME_UNKNOWN;

        public boolean isTerminal() {
            return switch (this) {
                case COMPLETED, FAILED, REJECTED, CANCELED, EXPIRED, OUTCOME_UNKNOWN -> true;
                default -> false;
            };
        }
    }

    public record Correlation(String threadId, String turnId, String toolCallId) {
        public Correlation {
            requireText(threadId, "threadId");
            requireText(turnId, "turnId");
            requireText(toolCallId, "toolCallId");
        }
    }

    /** 仅保存重建安全 outbound envelope 所需的可信身份，不保存动作输入或 WebSocket 对象。 */
    public record ConnectionContext(
            String reservationId,
            String webSocketSessionId,
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long identityEpoch,
            String userId,
            String tenantId,
            String platformId
    ) {
        public ConnectionContext {
            requireText(reservationId, "reservationId");
            requireText(webSocketSessionId, "webSocketSessionId");
            requireText(desktopInstanceId, "desktopInstanceId");
            requireText(desktopSessionId, "desktopSessionId");
            requireText(authSessionId, "authSessionId");
            requireText(userId, "userId");
            requireText(tenantId, "tenantId");
            requireText(platformId, "platformId");
            if (identityEpoch <= 0) {
                throw new IllegalArgumentException("identityEpoch must be positive");
            }
        }

        @Override
        public String toString() {
            return "ConnectionContext(reservationId=[REDACTED], webSocketSessionId=[REDACTED], "
                    + "desktopInstanceId=[REDACTED], desktopSessionId=[REDACTED], "
                    + "authSessionId=[REDACTED], identityEpoch=" + identityEpoch
                    + ", userId=[REDACTED], tenantId=[REDACTED], platformId=[REDACTED])";
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
