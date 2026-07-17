package com.wzx.babiq.server.application.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcMessage;
import com.wzx.babiq.server.api.JsonRpcMultiMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.action.ApplicationOutboundJsonRpcClient;
import com.wzx.babiq.server.application.action.ApplicationMessageSequence;
import com.wzx.babiq.server.application.action.PendingApplicationAction;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopHandshakeInterceptor;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.protocol.ApplicationActionMessage;
import com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 接收桌面动作进度/终态，并提供 status/cancel 的服务端主动请求适配。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class ApplicationActionProtocolHandler
        implements JsonRpcMultiMethodHandler, PendingApplicationActions.StatusQuery, PendingApplicationActions.CancelSender {

    private static final String ACCEPTED = "application/action/accepted";
    private static final String PREVIEWED = "application/action/previewed";
    private static final String APPROVAL_REQUIRED = "application/action/approval-required";
    private static final String RUNNING = "application/action/running";
    private static final String COMPLETED = "application/action/completed";
    private static final String FAILED = "application/action/failed";
    private static final String REJECTED = "application/action/rejected";
    private static final String CANCELED = "application/action/canceled";
    private static final String EXPIRED = "application/action/expired";
    private static final String OUTCOME_UNKNOWN = "application/action/outcome-unknown";
    private static final String STATUS = "application/action/status";
    private static final String RESULT_GET = "application/action/result/get";
    private static final String CANCEL = "application/action/cancel";
    private static final Duration OUTBOUND_TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PendingApplicationActions actions;
    private final ApplicationIdentityRegistry identities;
    private final BusinessDesktopConnectionRegistry connections;
    private final ApplicationOutboundJsonRpcClient outbound;
    private final ApplicationMessageSequence messageSequence;

    /** 发送真实动作 request；注册 pending 必须先由调用方完成，避免响应早于 waiter。 */
    public CompletableFuture<JsonRpcMessage> sendActionRequest(
            PendingApplicationAction action,
            JsonNode payload) {
        PendingApplicationAction.ConnectionContext context = action.connectionContext();
        if (context == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("application action transport is unavailable"));
        }
        ApplicationActionMessage message = new ApplicationActionMessage(
                "1.0",
                context.desktopInstanceId(),
                context.desktopSessionId(),
                context.authSessionId(),
                context.identityEpoch(),
                messageSequence.next(context.desktopSessionId()),
                Instant.now().toString(),
                context.userId(),
                context.tenantId(),
                context.platformId(),
                action.correlation().threadId(),
                action.correlation().turnId(),
                action.correlation().toolCallId(),
                action.executionId(),
                payload);
        CompletableFuture<JsonRpcMessage> request;
        try {
            request = outbound.request(
                    context.webSocketSessionId(), "application/action/request", message, OUTBOUND_TIMEOUT);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new ActionRequestAcknowledgementUncertain(
                    "application action acknowledgement transport failed", failure));
        }
        return request.handle((response, failure) -> {
            if (failure != null) {
                throw new CompletionException(new ActionRequestAcknowledgementUncertain(
                        "application action acknowledgement transport failed", unwrap(failure)));
            }
            return requireActionAck(action.executionId(), response);
        });
    }

    private JsonRpcMessage requireActionAck(String executionId, JsonRpcMessage message) {
        if (message instanceof JsonRpcMessage.ErrorResponse) {
            throw new ActionRequestAcknowledgementUncertain(
                    "desktop application action acknowledgement returned an error");
        }
        if (!(message instanceof JsonRpcMessage.Response response)) {
            throw new ActionRequestAcknowledgementUncertain(
                    "desktop returned an invalid application action acknowledgement");
        }
        JsonNode result = JSON.valueToTree(response.result());
        if (!executionId.equals(result.path("executionId").asText())) {
            throw new ActionRequestAcknowledgementUncertain(
                    "desktop application action acknowledgement correlation mismatch");
        }
        JsonNode accepted = result.get("accepted");
        if (accepted == null || !accepted.isBoolean()) {
            throw new ActionRequestAcknowledgementUncertain(
                    "desktop application action acknowledgement accepted flag is invalid");
        }
        if (!accepted.booleanValue()) {
            throw new ConfirmedActionRequestRejection(
                    "remote_request_failed", "desktop rejected application action request");
        }
        return message;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public ApplicationActionProtocolHandler(
            PendingApplicationActions actions,
            ApplicationIdentityRegistry identities,
            BusinessDesktopConnectionRegistry connections,
            ApplicationOutboundJsonRpcClient outbound) {
        this(actions, identities, connections, outbound, new ApplicationMessageSequence());
    }

    @Autowired
    public ApplicationActionProtocolHandler(
            PendingApplicationActions actions,
            ApplicationIdentityRegistry identities,
            BusinessDesktopConnectionRegistry connections,
            ApplicationOutboundJsonRpcClient outbound,
            ApplicationMessageSequence messageSequence) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.messageSequence = Objects.requireNonNull(messageSequence, "messageSequence");
        actions.bindStatusQuery(this);
        actions.bindCancelSender(this);
    }

    @Override
    public Set<String> methods() {
        return Set.of(
                ACCEPTED, PREVIEWED, APPROVAL_REQUIRED, RUNNING,
                COMPLETED, FAILED, REJECTED, CANCELED, EXPIRED, OUTCOME_UNKNOWN,
                STATUS, RESULT_GET);
    }

    @Override
    public Object handle(String method, JsonNode params, WebSocketSession session) {
        try {
            TrustedDesktopConnection connection = requireTrustedConnection(session);
            TrustedBusinessIdentity identity = identities.current(connection)
                    .orElseThrow(() -> new IllegalArgumentException("Authenticated identity is required"));
            ApplicationActionMessage message = JSON.convertValue(params, ApplicationActionMessage.class);
            validateMessage(message, identity);
            PendingApplicationAction.Correlation correlation = correlation(message);
            PendingApplicationAction.ConnectionContext connectionContext = connectionContext(connection, identity);
            PendingApplicationAction authorized = actions.findAuthorized(
                            message.executionId(), correlation, connectionContext)
                    .orElseThrow(() -> new IllegalArgumentException("Application action identity does not match"));
            return switch (method) {
                case ACCEPTED -> transition(actions.acceptedAuthorized(
                        message.executionId(), correlation, connectionContext));
                case PREVIEWED -> transition(actions.previewedAuthorized(
                        message.executionId(), correlation, connectionContext));
                case APPROVAL_REQUIRED -> transition(actions.approvalRequiredAuthorized(
                        message.executionId(), correlation, connectionContext));
                case RUNNING -> transition(actions.runningAuthorized(
                        message.executionId(), correlation, connectionContext));
                case COMPLETED -> terminal(message, correlation, connectionContext,
                        PendingApplicationAction.State.COMPLETED);
                case FAILED -> terminal(message, correlation, connectionContext,
                        PendingApplicationAction.State.FAILED);
                case REJECTED -> terminal(message, correlation, connectionContext,
                        PendingApplicationAction.State.REJECTED);
                case CANCELED -> terminal(message, correlation, connectionContext,
                        PendingApplicationAction.State.CANCELED);
                case EXPIRED -> terminal(message, correlation, connectionContext,
                        PendingApplicationAction.State.EXPIRED);
                case OUTCOME_UNKNOWN -> terminal(message, correlation, connectionContext,
                        PendingApplicationAction.State.OUTCOME_UNKNOWN);
                case STATUS -> status(authorized, false);
                case RESULT_GET -> status(authorized, true);
                default -> throw new IllegalArgumentException("Unsupported application action method");
            };
        } catch (JsonRpcException exception) {
            throw exception;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "Invalid application action parameters");
        }
    }

    @Override
    public CompletableFuture<PendingApplicationActions.RemoteStatus> query(PendingApplicationAction action) {
        PendingApplicationAction.ConnectionContext context = action.connectionContext();
        if (context == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("application action transport is unavailable"));
        }
        return outbound.request(context.webSocketSessionId(), STATUS,
                        safeOutboundMessage(action, context, "status_query"), OUTBOUND_TIMEOUT)
                .thenCompose(message -> {
                    PendingApplicationActions.RemoteStatus status = remoteStatus(action.executionId(), message);
                    if (status.terminal() == null) {
                        return CompletableFuture.completedFuture(status);
                    }
                    return outbound.request(context.webSocketSessionId(), RESULT_GET,
                                    safeOutboundMessage(action, context, "result_query"), OUTBOUND_TIMEOUT)
                            .thenApply(result -> remoteResult(action.executionId(), status.terminal(), result));
                });
    }

    @Override
    public CompletableFuture<Boolean> send(PendingApplicationAction action) {
        PendingApplicationAction.ConnectionContext context = action.connectionContext();
        if (context == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("application action transport is unavailable"));
        }
        return outbound.request(context.webSocketSessionId(), CANCEL,
                        safeOutboundMessage(action, context, "cancel_requested"), OUTBOUND_TIMEOUT)
                .thenApply(this::cancelConfirmed);
    }

    private Object terminal(
            ApplicationActionMessage message,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext,
            PendingApplicationAction.State state) {
        try {
            ApplicationProtocolValidator.validateActionResultSize(JSON.writeValueAsBytes(message.payload()));
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Cannot encode application action result", exception);
        }
        boolean accepted = actions.terminalAuthorized(
                message.executionId(), correlation, connectionContext, state, message.payload());
        return transition(accepted);
    }

    private Object status(PendingApplicationAction action, boolean requireTerminal) {
        if (requireTerminal && !action.isTerminal()) {
            throw new IllegalArgumentException("Application action is not terminal");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", action.executionId());
        result.put("state", wireState(action.state()));
        if (requireTerminal && action.payload() != null) {
            JsonNode payload = action.payload();
            if (payload.has("output")) {
                result.put("output", payload.get("output"));
            }
            if (payload.has("errorCode")) {
                result.put("errorCode", payload.get("errorCode").asText());
            }
        }
        Map<String, Object> safeResult = Map.copyOf(result);
        if (requireTerminal) {
            try {
                ApplicationProtocolValidator.validateActionResultSize(JSON.writeValueAsBytes(safeResult));
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException("Cannot encode application action result", exception);
            }
        }
        return safeResult;
    }

    private Map<String, Boolean> transition(boolean accepted) {
        if (!accepted) {
            throw new IllegalArgumentException("Application action transition was rejected");
        }
        return Map.of("accepted", true);
    }

    private PendingApplicationActions.RemoteStatus remoteStatus(String expectedExecutionId, JsonRpcMessage message) {
        if (!(message instanceof JsonRpcMessage.Response response)) {
            throw new IllegalStateException("Application action status query failed");
        }
        JsonNode result = JSON.valueToTree(response.result());
        String executionId = result.path("executionId").asText();
        if (executionId.isBlank() || !expectedExecutionId.equals(executionId)) {
            throw new IllegalStateException("Application action status correlation failed");
        }
        String state = result.path("state").asText();
        return switch (state) {
            case "succeeded" -> PendingApplicationActions.RemoteStatus.terminal(PendingApplicationAction.State.COMPLETED);
            case "failed" -> PendingApplicationActions.RemoteStatus.terminal(PendingApplicationAction.State.FAILED);
            case "rejected" -> PendingApplicationActions.RemoteStatus.terminal(PendingApplicationAction.State.REJECTED);
            case "canceled" -> PendingApplicationActions.RemoteStatus.terminal(PendingApplicationAction.State.CANCELED);
            case "expired" -> PendingApplicationActions.RemoteStatus.terminal(PendingApplicationAction.State.EXPIRED);
            case "outcome_unknown" -> PendingApplicationActions.RemoteStatus.terminal(
                    PendingApplicationAction.State.OUTCOME_UNKNOWN);
            case "received", "validating", "previewed", "waiting_approval", "executing" ->
                    PendingApplicationActions.RemoteStatus.running();
            default -> throw new IllegalStateException("Unknown application action status");
        };
    }

    private PendingApplicationActions.RemoteStatus remoteResult(
            String expectedExecutionId,
            PendingApplicationAction.State expectedState,
            JsonRpcMessage message) {
        if (!(message instanceof JsonRpcMessage.Response response)) {
            throw new IllegalStateException("Application action result query failed");
        }
        JsonNode result = JSON.valueToTree(response.result());
        try {
            ApplicationProtocolValidator.validateActionResultSize(JSON.writeValueAsBytes(result));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot encode application action result", exception);
        }
        String executionId = result.path("executionId").asText();
        if (executionId.isBlank() || !expectedExecutionId.equals(executionId)) {
            throw new IllegalStateException("Application action result correlation failed");
        }
        if (!wireState(expectedState).equals(result.path("state").asText())) {
            throw new IllegalStateException("Application action result state mismatch");
        }
        com.fasterxml.jackson.databind.node.ObjectNode payload = JSON.createObjectNode();
        if (result.has("output")) {
            payload.set("output", result.get("output"));
        }
        if (result.has("errorCode")) {
            payload.put("errorCode", result.get("errorCode").asText());
        }
        return PendingApplicationActions.RemoteStatus.terminal(expectedState, payload);
    }

    private boolean cancelConfirmed(JsonRpcMessage message) {
        if (!(message instanceof JsonRpcMessage.Response response)) {
            return false;
        }
        return JSON.valueToTree(response.result()).path("accepted").asBoolean(false);
    }

    private ApplicationActionMessage safeOutboundMessage(
            PendingApplicationAction action,
            PendingApplicationAction.ConnectionContext context,
            String state) {
        return new ApplicationActionMessage(
                "1.0",
                context.desktopInstanceId(),
                context.desktopSessionId(),
                context.authSessionId(),
                context.identityEpoch(),
                messageSequence.next(context.desktopSessionId()),
                Instant.now().toString(),
                context.userId(),
                context.tenantId(),
                context.platformId(),
                action.correlation().threadId(),
                action.correlation().turnId(),
                action.correlation().toolCallId(),
                action.executionId(),
                JSON.createObjectNode().put("state", state));
    }

    private void validateMessage(ApplicationActionMessage message, TrustedBusinessIdentity identity) {
        ApplicationProtocolValidator.validate(message);
        if (!identity.desktopInstanceId().equals(message.desktopInstanceId())
                || !identity.desktopSessionId().equals(message.desktopSessionId())
                || !identity.authSessionId().equals(message.authSessionId())
                || identity.identityEpoch() != message.identityEpoch()
                || !identity.userId().equals(message.userId())
                || !identity.tenantId().equals(message.tenantId())
                || !identity.platformId().equals(message.platformId())) {
            throw new IllegalArgumentException("Application action identity does not match");
        }
        requireText(message.threadId(), "threadId");
        requireText(message.turnId(), "turnId");
        requireText(message.toolCallId(), "toolCallId");
        requireText(message.executionId(), "executionId");
    }

    private TrustedDesktopConnection requireTrustedConnection(WebSocketSession session) {
        if (session == null || session.getId() == null || session.getAttributes() == null) {
            throw new IllegalArgumentException("Trusted WebSocket session is required");
        }
        String reservationId = attribute(session, BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE);
        String desktopInstanceId = attribute(session,
                BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE);
        String desktopSessionId = attribute(session,
                BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE);
        TrustedDesktopConnection connection = connections.findByDesktopSessionId(desktopSessionId)
                .orElseThrow(() -> new IllegalArgumentException("Finalized business desktop connection is required"));
        if (!connection.reservationId().equals(reservationId)
                || !connection.desktopInstanceId().equals(desktopInstanceId)
                || !connection.desktopSessionId().equals(desktopSessionId)
                || !connection.webSocketSessionId().equals(session.getId())) {
            throw new IllegalArgumentException("WebSocket attributes do not match finalized connection");
        }
        return connection;
    }

    private static PendingApplicationAction.Correlation correlation(ApplicationActionMessage message) {
        return new PendingApplicationAction.Correlation(
                message.threadId(), message.turnId(), message.toolCallId());
    }

    private static PendingApplicationAction.ConnectionContext connectionContext(
            TrustedDesktopConnection connection,
            TrustedBusinessIdentity identity) {
        return new PendingApplicationAction.ConnectionContext(
                connection.reservationId(),
                connection.webSocketSessionId(),
                connection.desktopInstanceId(),
                connection.desktopSessionId(),
                identity.authSessionId(),
                identity.identityEpoch(),
                identity.userId(),
                identity.tenantId(),
                identity.platformId());
    }

    private static String wireState(PendingApplicationAction.State state) {
        return switch (state) {
            case REQUESTED -> "requested";
            case ACCEPTED -> "accepted";
            case PREVIEWED -> "previewed";
            case APPROVAL_REQUIRED -> "waiting_approval";
            case RUNNING -> "executing";
            case COMPLETED -> "succeeded";
            case FAILED -> "failed";
            case REJECTED -> "rejected";
            case CANCELED -> "canceled";
            case EXPIRED -> "expired";
            case OUTCOME_UNKNOWN -> "outcome_unknown";
        };
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private String attribute(WebSocketSession session, String name) {
        Object value = session.getAttributes().get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Missing trusted WebSocket attribute: " + name);
        }
        return text;
    }

}
