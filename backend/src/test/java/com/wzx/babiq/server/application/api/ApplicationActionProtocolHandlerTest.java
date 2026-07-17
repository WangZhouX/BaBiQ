package com.wzx.babiq.server.application.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.action.ApplicationOutboundJsonRpcClient;
import com.wzx.babiq.server.application.action.PendingApplicationAction;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopHandshakeInterceptor;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证桌面动作进度、终态和查询只在可信身份范围内进入 pending registry。 */
class ApplicationActionProtocolHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PendingApplicationActions actions = mock(PendingApplicationActions.class);
    private final ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
    private final BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
    private final ApplicationOutboundJsonRpcClient outbound = mock(ApplicationOutboundJsonRpcClient.class);
    private final WebSocketSession session = mock(WebSocketSession.class);
    private final TrustedDesktopConnection connection = new TrustedDesktopConnection(
            "reservation-1", "desktop-1", "desktop-session-1", "ws-1");
    private final TrustedBusinessIdentity identity = new TrustedBusinessIdentity(
            "reservation-1", "ws-1", "desktop-1", "desktop-session-1", "auth-session-1", 8,
            "user-1", "tenant-1", "platform-1", Set.of("lawyer"), Set.of("case:read"));
    private ApplicationActionProtocolHandler handler;

    @BeforeEach
    void setUp() {
        when(session.getId()).thenReturn("ws-1");
        when(session.getAttributes()).thenReturn(Map.of(
                BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, "reservation-1",
                BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, "desktop-1",
                BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, "desktop-session-1"));
        when(connections.findByDesktopSessionId("desktop-session-1")).thenReturn(Optional.of(connection));
        when(identities.current(connection)).thenReturn(Optional.of(identity));
        when(actions.findAuthorized(anyString(), any(), any())).thenAnswer(invocation -> Optional.of(
                action(PendingApplicationAction.State.REQUESTED)
                        .withConnectionContext(invocation.getArgument(2))));
        handler = new ApplicationActionProtocolHandler(actions, identities, connections, outbound);
    }

    @Test
    void declaresAllInboundProgressTerminalAndQueryMethods() {
        assertThat(handler.methods()).containsExactlyInAnyOrder(
                "application/action/accepted",
                "application/action/previewed",
                "application/action/approval-required",
                "application/action/running",
                "application/action/completed",
                "application/action/failed",
                "application/action/rejected",
                "application/action/canceled",
                "application/action/expired",
                "application/action/outcome-unknown",
                "application/action/status",
                "application/action/result/get");
        assertThat(handler.methods()).doesNotContain("application/action/cancel");
    }

    @Test
    void outboundActionRequestUsesExactConnectionCorrelationIdentityAndRequiresMatchingAck() {
        PendingApplicationAction requested = action(PendingApplicationAction.State.REQUESTED)
                .withConnectionContext(connectionContext());
        CompletableFuture<com.wzx.babiq.server.api.JsonRpcMessage> response = new CompletableFuture<>();
        when(outbound.request(eq("ws-1"), eq("application/action/request"), any(), any()))
                .thenReturn(response);

        CompletableFuture<com.wzx.babiq.server.api.JsonRpcMessage> sent = handler.sendActionRequest(
                requested, objectMapper.createObjectNode().put("actionId", "framework.demo"));
        ArgumentCaptor<Object> envelope = ArgumentCaptor.forClass(Object.class);
        verify(outbound).request(eq("ws-1"), eq("application/action/request"), envelope.capture(), any());
        assertThat(envelope.getValue())
                .isInstanceOfSatisfying(com.wzx.babiq.server.application.protocol.ApplicationActionMessage.class,
                        message -> {
                            assertThat(message.executionId()).isEqualTo("execution-1");
                            assertThat(message.threadId()).isEqualTo("thread-1");
                            assertThat(message.turnId()).isEqualTo("turn-1");
                            assertThat(message.toolCallId()).isEqualTo("tool-call-1");
                            assertThat(message.payload().path("actionId").asText()).isEqualTo("framework.demo");
                        });

        response.complete(new com.wzx.babiq.server.api.JsonRpcMessage.Response(
                "2.0", 1L, Map.of("executionId", "execution-1", "accepted", true)));
        assertThat(sent.join()).isInstanceOf(com.wzx.babiq.server.api.JsonRpcMessage.Response.class);
    }

    @Test
    void outboundActionRequestSeparatesConfirmedNegativeAcknowledgementsFromTransportUncertainty() {
        PendingApplicationAction requested = action(PendingApplicationAction.State.REQUESTED)
                .withConnectionContext(connectionContext());
        when(outbound.request(eq("ws-1"), eq("application/action/request"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new com.wzx.babiq.server.api.JsonRpcMessage.Response(
                                "2.0", 1L, Map.of("executionId", "execution-1", "accepted", false))));
        assertThatThrownBy(() -> handler.sendActionRequest(
                requested, objectMapper.createObjectNode()).join())
                .hasRootCauseInstanceOf(ConfirmedActionRequestRejection.class);

        java.util.List<com.wzx.babiq.server.api.JsonRpcMessage> uncertain = java.util.List.of(
                new com.wzx.babiq.server.api.JsonRpcMessage.Response(
                        "2.0", 2L, Map.of("executionId", "execution-other", "accepted", true)),
                new com.wzx.babiq.server.api.JsonRpcMessage.Response(
                        "2.0", 3L, Map.of("executionId", "execution-1")),
                new com.wzx.babiq.server.api.JsonRpcMessage.Response(
                        "2.0", 4L, Map.of("executionId", "execution-1", "accepted", "false")),
                new com.wzx.babiq.server.api.JsonRpcMessage.Response(
                        "2.0", 5L, Map.of("executionId", "execution-1", "accepted", 0)),
                new com.wzx.babiq.server.api.JsonRpcMessage.Response(
                        "2.0", 6L, java.util.Map.of("executionId", "execution-1", "accepted",
                                com.fasterxml.jackson.databind.node.NullNode.getInstance())),
                new com.wzx.babiq.server.api.JsonRpcMessage.Notification("2.0", "unexpected", Map.of()),
                com.wzx.babiq.server.api.JsonRpcMessage.ErrorResponse.of(
                        7L, com.wzx.babiq.server.api.error.JsonRpcErrorCode.INVALID_PARAMS,
                        "request rejected", null));
        for (com.wzx.babiq.server.api.JsonRpcMessage response : uncertain) {
            when(outbound.request(eq("ws-1"), eq("application/action/request"), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(response));

            assertThatThrownBy(() -> handler.sendActionRequest(
                    requested, objectMapper.createObjectNode()).join())
                    .isInstanceOfSatisfying(java.util.concurrent.CompletionException.class,
                            failure -> assertThat(failure.getCause())
                                    .isInstanceOf(ActionRequestAcknowledgementUncertain.class));
        }

        when(outbound.request(eq("ws-1"), eq("application/action/request"), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new java.util.concurrent.TimeoutException("ack lost")));
        assertThatThrownBy(() -> handler.sendActionRequest(requested, objectMapper.createObjectNode()).join())
                .isInstanceOfSatisfying(java.util.concurrent.CompletionException.class,
                        failure -> assertThat(failure.getCause())
                                .isInstanceOf(ActionRequestAcknowledgementUncertain.class));
    }

    @Test
    void progressMethodsMapToPendingLifecycle() throws Exception {
        when(actions.acceptedAuthorized("execution-1", correlation(), connectionContext())).thenReturn(true);
        when(actions.previewedAuthorized("execution-1", correlation(), connectionContext())).thenReturn(true);
        when(actions.approvalRequiredAuthorized("execution-1", correlation(), connectionContext())).thenReturn(true);
        when(actions.runningAuthorized("execution-1", correlation(), connectionContext())).thenReturn(true);

        handler.handle("application/action/accepted", message("accepted"), session);
        handler.handle("application/action/previewed", message("previewed"), session);
        handler.handle("application/action/approval-required", message("waiting_approval"), session);
        handler.handle("application/action/running", message("executing"), session);

        verify(actions).acceptedAuthorized("execution-1", correlation(), connectionContext());
        verify(actions).previewedAuthorized("execution-1", correlation(), connectionContext());
        verify(actions).approvalRequiredAuthorized("execution-1", correlation(), connectionContext());
        verify(actions).runningAuthorized("execution-1", correlation(), connectionContext());
    }

    @Test
    void sixTerminalMethodsMapToExactTerminalStates() throws Exception {
        for (var row : Map.of(
                "application/action/completed", PendingApplicationAction.State.COMPLETED,
                "application/action/failed", PendingApplicationAction.State.FAILED,
                "application/action/rejected", PendingApplicationAction.State.REJECTED,
                "application/action/canceled", PendingApplicationAction.State.CANCELED,
                "application/action/expired", PendingApplicationAction.State.EXPIRED,
                "application/action/outcome-unknown", PendingApplicationAction.State.OUTCOME_UNKNOWN).entrySet()) {
            when(actions.terminalAuthorized("execution-1", correlation(), connectionContext(), row.getValue(),
                    message("terminal").path("payload")))
                    .thenReturn(true);
            handler.handle(row.getKey(), message("terminal"), session);
            verify(actions).terminalAuthorized("execution-1", correlation(), connectionContext(), row.getValue(),
                    message("terminal").path("payload"));
        }
    }

    @Test
    void statusAndResultQueriesReturnOnlyMatchingPendingOrTerminalSnapshot() throws Exception {
        PendingApplicationAction running = action(PendingApplicationAction.State.RUNNING);
        PendingApplicationAction completed = action(PendingApplicationAction.State.COMPLETED);
        when(actions.findAuthorized(eq("execution-1"), eq(correlation()), any()))
                .thenReturn(Optional.of(running.withConnectionContext(connectionContext())),
                        Optional.of(completed.withConnectionContext(connectionContext())));

        Object status = handler.handle("application/action/status", message("status_query"), session);
        Object result = handler.handle("application/action/result/get", message("result_query"), session);

        assertThat(status).isEqualTo(Map.of("executionId", "execution-1", "state", "executing"));
        assertThat(result).isInstanceOfSatisfying(Map.class, map -> {
            assertThat(map.get("executionId")).isEqualTo("execution-1");
            assertThat(map.get("state")).isEqualTo("succeeded");
        });
    }

    @Test
    void mismatchedIdentityIsRejectedBeforePendingMutation() {
        ObjectNode mismatched = message("accepted");
        mismatched.put("tenantId", "tenant-other");

        assertThatThrownBy(() -> handler.handle("application/action/accepted", mismatched, session))
                .isInstanceOf(JsonRpcException.class);
        verify(actions, never()).accepted(any(), any());
    }

    @Test
    void executeStatusQueryUsesOutboundStatusWithSafePayloadAndExactSession() throws Exception {
        when(actions.acceptedAuthorized("execution-1", correlation(), connectionContext())).thenReturn(true);
        when(outbound.request(eq("ws-1"), eq("application/action/status"), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        com.wzx.babiq.server.api.JsonRpcMessage.Response.ok(
                                1L, Map.of("executionId", "execution-1", "state", "executing"))));
        ObjectNode inbound = message("accepted");
        inbound.withObject("payload").put("secretInput", "must-not-be-retained");
        handler.handle("application/action/accepted", inbound, session);
        PendingApplicationAction action = action(PendingApplicationAction.State.RUNNING)
                .withConnectionContext(connectionContext());

        assertThat(handler.query(action).join())
                .isEqualTo(PendingApplicationActions.RemoteStatus.running());

        ArgumentCaptor<Object> params = ArgumentCaptor.forClass(Object.class);
        verify(outbound).request(eq("ws-1"), eq("application/action/status"), params.capture(), any());
        assertThat(params.getValue().toString()).doesNotContain("must-not-be-retained");
        assertThat(((com.wzx.babiq.server.application.protocol.ApplicationActionMessage) params.getValue())
                .payload().path("state").asText()).isEqualTo("status_query");
    }

    @Test
    void executeStatusQueryRejectsMissingOrMismatchedExecutionId() {
        PendingApplicationAction running = action(PendingApplicationAction.State.RUNNING)
                .withConnectionContext(connectionContext());
        for (Map<String, Object> result : java.util.List.of(
                Map.<String, Object>of("state", "succeeded"),
                Map.<String, Object>of("executionId", "execution-other", "state", "succeeded"))) {
            org.mockito.Mockito.reset(outbound);
            when(outbound.request(eq("ws-1"), eq("application/action/status"), any(), any()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                            com.wzx.babiq.server.api.JsonRpcMessage.Response.ok(1L, result)));

            assertThatThrownBy(() -> handler.query(running).join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class);
        }
    }

    @Test
    void executeStatusQueryRejectsUnknownOrBlankState() {
        PendingApplicationAction running = action(PendingApplicationAction.State.RUNNING)
                .withConnectionContext(connectionContext());
        for (String state : java.util.List.of("", "mystery_state")) {
            org.mockito.Mockito.reset(outbound);
            when(outbound.request(eq("ws-1"), eq("application/action/status"), any(), any()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                            com.wzx.babiq.server.api.JsonRpcMessage.Response.ok(
                                    1L, Map.of("executionId", "execution-1", "state", state))));

            assertThatThrownBy(() -> handler.query(running).join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class);
        }
    }

    @Test
    void outboundStatusThenCancelUsesIncreasingSequenceForTheDesktopSession() throws Exception {
        when(outbound.request(eq("ws-1"), eq("application/action/status"), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        com.wzx.babiq.server.api.JsonRpcMessage.Response.ok(
                                1L, Map.of("executionId", "execution-1", "state", "executing"))));
        when(outbound.request(eq("ws-1"), eq("application/action/cancel"), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        com.wzx.babiq.server.api.JsonRpcMessage.Response.ok(2L, Map.of("accepted", true))));
        PendingApplicationAction running = action(PendingApplicationAction.State.RUNNING)
                .withConnectionContext(connectionContext());

        handler.query(running).join();
        handler.send(running).join();

        ArgumentCaptor<Object> params = ArgumentCaptor.forClass(Object.class);
        verify(outbound).request(eq("ws-1"), eq("application/action/status"), params.capture(), any());
        verify(outbound).request(eq("ws-1"), eq("application/action/cancel"), params.capture(), any());
        assertThat(params.getAllValues())
                .extracting(value -> ((com.wzx.babiq.server.application.protocol.ApplicationActionMessage) value)
                        .sequence())
                .containsExactly(1L, 2L);
    }

    @Test
    void executeStatusTerminalFetchesAndReturnsTheRedactedResultPayload() {
        when(outbound.request(eq("ws-1"), eq("application/action/status"), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        com.wzx.babiq.server.api.JsonRpcMessage.Response.ok(
                                1L, Map.of("executionId", "execution-1", "state", "succeeded"))));
        when(outbound.request(eq("ws-1"), eq("application/action/result/get"), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        com.wzx.babiq.server.api.JsonRpcMessage.Response.ok(
                                2L, Map.of(
                                        "executionId", "execution-1",
                                        "state", "succeeded",
                                        "output", "safe-output",
                                        "errorCode", "remote_request_failed"))));
        PendingApplicationAction running = action(PendingApplicationAction.State.RUNNING)
                .withConnectionContext(connectionContext());

        PendingApplicationActions.RemoteStatus status = handler.query(running).join();

        assertThat(status.terminal()).isEqualTo(PendingApplicationAction.State.COMPLETED);
        assertThat(status.payload()).isNotNull();
        assertThat(status.payload().path("output").asText()).isEqualTo("safe-output");
        assertThat(status.payload().path("errorCode").asText()).isEqualTo("remote_request_failed");
        verify(outbound).request(eq("ws-1"), eq("application/action/result/get"), any(), any());
    }

    @Test
    void executeTerminalStatusRejectsInvalidResultCorrelationOrState() {
        PendingApplicationAction running = action(PendingApplicationAction.State.RUNNING)
                .withConnectionContext(connectionContext());
        for (Map<String, Object> result : java.util.List.of(
                Map.<String, Object>of("state", "succeeded", "output", "missing-id"),
                Map.<String, Object>of(
                        "executionId", "execution-other", "state", "succeeded", "output", "wrong-id"),
                Map.<String, Object>of(
                        "executionId", "execution-1", "state", "failed", "output", "wrong-state"))) {
            org.mockito.Mockito.reset(outbound);
            when(outbound.request(eq("ws-1"), eq("application/action/status"), any(), any()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                            com.wzx.babiq.server.api.JsonRpcMessage.Response.ok(
                                    1L, Map.of("executionId", "execution-1", "state", "succeeded"))));
            when(outbound.request(eq("ws-1"), eq("application/action/result/get"), any(), any()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                            com.wzx.babiq.server.api.JsonRpcMessage.Response.ok(2L, result)));

            assertThatThrownBy(() -> handler.query(running).join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class);
        }
    }

    @Test
    void cancelSenderUsesOutboundCancelAndNeverAcceptsInboundCancel() throws Exception {
        when(actions.acceptedAuthorized("execution-1", correlation(), connectionContext())).thenReturn(true);
        when(outbound.request(eq("ws-1"), eq("application/action/cancel"), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        com.wzx.babiq.server.api.JsonRpcMessage.Response.ok(2L, Map.of("accepted", true))));
        handler.handle("application/action/accepted", message("accepted"), session);

        assertThat(handler.send(action(PendingApplicationAction.State.RUNNING)
                .withConnectionContext(connectionContext())).join()).isTrue();

        verify(outbound).request(eq("ws-1"), eq("application/action/cancel"), any(), any());
        assertThatThrownBy(() -> handler.handle("application/action/cancel", message("cancel"), session))
                .isInstanceOf(JsonRpcException.class);
    }

    @Test
    void resultGetReturnsOnlyWhitelistedRedactedTerminalFields() throws Exception {
        ObjectNode terminalPayload = objectMapper.createObjectNode();
        terminalPayload.put("output", "safe-summary");
        terminalPayload.put("errorCode", "remote_request_failed");
        terminalPayload.put("secretInput", "must-not-leak");
        PendingApplicationAction completed = new PendingApplicationAction(
                "execution-1", correlation(), PendingApplicationAction.Path.READ_ONLY,
                PendingApplicationAction.State.COMPLETED, terminalPayload, "secret-reason",
                Instant.parse("2026-07-17T00:00:00Z"), connectionContext());
        when(actions.findAuthorized(eq("execution-1"), eq(correlation()), any()))
                .thenReturn(Optional.of(completed));

        Object result = handler.handle("application/action/result/get", message("result_query"), session);

        assertThat(result.toString())
                .contains("safe-summary", "remote_request_failed")
                .doesNotContain("must-not-leak", "secret-reason");
    }

    @Test
    void resultGetRejectsOversizedRedactedOutput() {
        ObjectNode terminalPayload = objectMapper.createObjectNode();
        terminalPayload.put("output", "x".repeat(
                com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator.MAX_ACTION_RESULT_BYTES + 1));
        PendingApplicationAction completed = new PendingApplicationAction(
                "execution-1", correlation(), PendingApplicationAction.Path.READ_ONLY,
                PendingApplicationAction.State.COMPLETED, terminalPayload, null,
                Instant.parse("2026-07-17T00:00:00Z"), connectionContext());
        when(actions.findAuthorized(eq("execution-1"), eq(correlation()), any()))
                .thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> handler.handle(
                "application/action/result/get", message("result_query"), session))
                .isInstanceOf(JsonRpcException.class);
    }

    @Test
    void oversizedDesktopTerminalIsRejectedBeforePendingMutation() {
        ObjectNode completed = message("succeeded");
        completed.withObject("payload").put(
                "output",
                "x".repeat(com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator
                        .MAX_ACTION_RESULT_BYTES + 1));

        assertThatThrownBy(() -> handler.handle("application/action/completed", completed, session))
                .isInstanceOf(JsonRpcException.class);

        verify(actions, never()).terminal(anyString(), any(), any(), any());
    }

    @Test
    void everyInboundMethodRejectsAStoredActionOwnedByAnotherConnectionBeforeMutationOrQuery() {
        for (String method : handler.methods()) {
            org.mockito.Mockito.reset(actions);
            when(actions.findAuthorized(eq("execution-1"), eq(correlation()), any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler.handle(method, message("state"), session))
                    .as(method)
                    .isInstanceOf(JsonRpcException.class);

            verify(actions).findAuthorized(eq("execution-1"), eq(correlation()), eq(connectionContext()));
            verify(actions, never()).accepted(anyString(), any());
            verify(actions, never()).previewed(anyString(), any());
            verify(actions, never()).approvalRequired(anyString(), any());
            verify(actions, never()).running(anyString(), any());
            verify(actions, never()).terminal(anyString(), any(), any(), any());
            verify(actions, never()).acceptedAuthorized(anyString(), any(), any());
            verify(actions, never()).previewedAuthorized(anyString(), any(), any());
            verify(actions, never()).approvalRequiredAuthorized(anyString(), any(), any());
            verify(actions, never()).runningAuthorized(anyString(), any(), any());
            verify(actions, never()).terminalAuthorized(anyString(), any(), any(), any(), any());
        }
        verifyNoInteractions(outbound);
    }

    @Test
    void everyTrustedIdentityFieldIsCheckedBeforeAuthorizedLookup() {
        Map<String, Object> forgedValues = new java.util.LinkedHashMap<>();
        forgedValues.put("desktopInstanceId", "desktop-other");
        forgedValues.put("desktopSessionId", "desktop-session-other");
        forgedValues.put("authSessionId", "auth-session-other");
        forgedValues.put("identityEpoch", 9L);
        forgedValues.put("userId", "user-other");
        forgedValues.put("tenantId", "tenant-other");
        forgedValues.put("platformId", "platform-other");

        for (Map.Entry<String, Object> forged : forgedValues.entrySet()) {
            org.mockito.Mockito.clearInvocations(actions);
            ObjectNode value = message("accepted");
            if (forged.getValue() instanceof Long number) {
                value.put(forged.getKey(), number);
            } else {
                value.put(forged.getKey(), forged.getValue().toString());
            }

            assertThatThrownBy(() -> handler.handle("application/action/accepted", value, session))
                    .as(forged.getKey())
                    .isInstanceOf(JsonRpcException.class);
            verify(actions, never()).findAuthorized(anyString(), any(), any());
        }
    }

    @Test
    void everyTrustedConnectionFieldIsCheckedBeforeAuthorizedLookup() {
        Map<String, Object> baseAttributes = new java.util.HashMap<>(session.getAttributes());

        for (String field : java.util.List.of(
                "reservationId", "webSocketSessionId", "desktopInstanceId", "desktopSessionId")) {
            org.mockito.Mockito.clearInvocations(actions);
            when(session.getId()).thenReturn("ws-1");
            Map<String, Object> attributes = new java.util.HashMap<>(baseAttributes);
            switch (field) {
                case "reservationId" -> attributes.put(
                        BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, "reservation-other");
                case "webSocketSessionId" -> when(session.getId()).thenReturn("ws-other");
                case "desktopInstanceId" -> attributes.put(
                        BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, "desktop-other");
                case "desktopSessionId" -> attributes.put(
                        BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, "desktop-session-other");
                default -> throw new IllegalStateException("Unexpected connection field");
            }
            when(session.getAttributes()).thenReturn(attributes);

            assertThatThrownBy(() -> handler.handle(
                    "application/action/accepted", message("accepted"), session))
                    .as(field)
                    .isInstanceOf(JsonRpcException.class);
            verify(actions, never()).findAuthorized(anyString(), any(), any());
        }
    }

    @Test
    void newIdentityCannotReadAnOldTerminalOrRecordALateTerminal() {
        when(actions.findAuthorized(eq("execution-1"), eq(correlation()), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle("application/action/result/get", message("result_query"), session))
                .isInstanceOf(JsonRpcException.class);
        assertThatThrownBy(() -> handler.handle("application/action/completed", message("succeeded"), session))
                .isInstanceOf(JsonRpcException.class);

        verify(actions, never()).terminal(anyString(), any(), any(), any());
        verify(actions, never()).terminalAuthorized(anyString(), any(), any(), any(), any());
    }

    private ObjectNode message(String state) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("protocolVersion", "1.0");
        value.put("desktopInstanceId", "desktop-1");
        value.put("desktopSessionId", "desktop-session-1");
        value.put("authSessionId", "auth-session-1");
        value.put("identityEpoch", 8);
        value.put("sequence", 12);
        value.put("generatedAt", Instant.parse("2026-07-17T00:00:00Z").toString());
        value.put("userId", "user-1");
        value.put("tenantId", "tenant-1");
        value.put("platformId", "platform-1");
        value.put("threadId", "thread-1");
        value.put("turnId", "turn-1");
        value.put("toolCallId", "tool-call-1");
        value.put("executionId", "execution-1");
        value.putObject("payload").put("state", state).put("output", "redacted");
        return value;
    }

    private PendingApplicationAction action(PendingApplicationAction.State state) {
        return new PendingApplicationAction(
                "execution-1", correlation(), PendingApplicationAction.Path.READ_ONLY, state,
                message("terminal").path("payload"), null, Instant.parse("2026-07-17T00:00:00Z"));
    }

    private static PendingApplicationAction.Correlation correlation() {
        return new PendingApplicationAction.Correlation("thread-1", "turn-1", "tool-call-1");
    }

    private static PendingApplicationAction.ConnectionContext connectionContext() {
        return new PendingApplicationAction.ConnectionContext(
                "reservation-1", "ws-1", "desktop-1", "desktop-session-1", "auth-session-1", 8,
                "user-1", "tenant-1", "platform-1");
    }
}
