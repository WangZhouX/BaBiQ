package com.wzx.babiq.server.application.scope;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.PendingApprovals;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.api.method.ApprovalRespondHandler;
import com.wzx.babiq.server.api.method.TurnStartHandler;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopHandshakeInterceptor;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.protocol.ApplicationIdentityMessage;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证旧 scope 请求与 identity/update 只在线性化顺序的一侧生效。 */
class BusinessTurnIdentityLinearizationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TrustedDesktopConnection CONNECTION = new TrustedDesktopConnection(
            "reservation", "desktop", "desktop-session", "ws");
    private static final BusinessIdentityScope OLD_SCOPE = BusinessIdentityScope.scoped(
            "desktop", "desktop-session", "auth-1", 1, "user-1", "tenant-1", "platform");

    @Test
    void identityUpdateWinningAfterScopeResolutionPreventsTurnStartSubmission() throws Exception {
        ConversationService conversations = new ConversationService();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry(
                (connection, oldIdentity, newIdentity) ->
                        conversations.expirePreExecutionTurns(OLD_SCOPE, "identity changed"));
        identities.bind(CONNECTION, identity(1, "auth-1", "user-1", "tenant-1"));
        BusinessIdentityScopeService scopes = pausingScopeService(identities);
        var thread = conversations.createThread("C:/business", OLD_SCOPE);
        TurnExecutor executor = mock(TurnExecutor.class);
        TurnStartHandler handler = new TurnStartHandler(
                conversations, JSON, executor, null, null, null, null, null, scopes);
        WebSocketSession session = session();
        CountDownLatch resolved = new CountDownLatch(1);
        CountDownLatch continueRequest = new CountDownLatch(1);
        pauseAfterResolve(scopes, session, resolved, continueRequest);

        CompletableFuture<Object> request = CompletableFuture.supplyAsync(() -> handler.handle(
                JSON.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of("type", "text", "text", "start"))), session));
        assertThat(resolved.await(3, TimeUnit.SECONDS)).isTrue();

        identities.update(CONNECTION, identity(2, "auth-2", "user-2", "tenant-2"));
        continueRequest.countDown();

        assertThatThrownBy(request::join).hasCauseInstanceOf(JsonRpcException.class);
        assertThat(conversations.hasActiveTurn(thread.id(), OLD_SCOPE)).isFalse();
        verify(executor, never()).submit(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(
                        com.wzx.babiq.server.attachment.PreparedTurnInput.class),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void identityUpdateWinningAfterScopeResolutionPreventsApprovalResumeSubmission() throws Exception {
        ConversationService conversations = new ConversationService();
        PendingApprovals approvals = new PendingApprovals();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry(
                (connection, oldIdentity, newIdentity) -> {
                    for (String threadId : conversations.expirePreExecutionTurns(OLD_SCOPE, "identity changed")) {
                        approvals.remove(threadId);
                    }
                });
        identities.bind(CONNECTION, identity(1, "auth-1", "user-1", "tenant-1"));
        BusinessIdentityScopeService scopes = pausingScopeService(identities);
        var thread = conversations.createThread("C:/business", OLD_SCOPE);
        var turn = conversations.startTurn(thread.id(), OLD_SCOPE);
        turn.start();
        turn.waitApproval();
        approvals.put(thread.id(), metadata());
        TurnExecutor executor = mock(TurnExecutor.class);
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                approvals, conversations, JSON, executor, new BaBiQMetrics(),
                null, null, null, null, null, scopes);
        WebSocketSession session = session();
        CountDownLatch resolved = new CountDownLatch(1);
        CountDownLatch continueRequest = new CountDownLatch(1);
        pauseAfterResolve(scopes, session, resolved, continueRequest);

        CompletableFuture<Object> request = CompletableFuture.supplyAsync(() -> handler.handle(
                JSON.valueToTree(Map.of(
                        "threadId", thread.id(), "turnId", turn.id(), "decision", "approve")), session));
        assertThat(resolved.await(3, TimeUnit.SECONDS)).isTrue();

        identities.update(CONNECTION, identity(2, "auth-2", "user-2", "tenant-2"));
        continueRequest.countDown();

        assertThatThrownBy(request::join).hasCauseInstanceOf(JsonRpcException.class);
        assertThat(turn.status()).isEqualTo(TurnStatus.EXPIRED);
        assertThat(approvals.peek(thread.id())).isNull();
        verify(executor, never()).submitResume(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private BusinessIdentityScopeService pausingScopeService(ApplicationIdentityRegistry identities) {
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        when(connections.findByDesktopSessionId("desktop-session")).thenReturn(Optional.of(CONNECTION));
        return spy(new BusinessIdentityScopeService(true, connections, identities));
    }

    private void pauseAfterResolve(
            BusinessIdentityScopeService scopes,
            WebSocketSession session,
            CountDownLatch resolved,
            CountDownLatch continueRequest) {
        doAnswer(invocation -> {
            BusinessIdentityScope scope = (BusinessIdentityScope) invocation.callRealMethod();
            resolved.countDown();
            if (!continueRequest.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test barrier timed out");
            }
            return scope;
        }).when(scopes).resolve(session);
    }

    private WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws");
        when(session.getAttributes()).thenReturn(Map.of(
                BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, "reservation",
                BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, "desktop",
                BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, "desktop-session"));
        return session;
    }

    private static ApplicationIdentityMessage identity(
            long epoch, String authSessionId, String userId, String tenantId) {
        return new ApplicationIdentityMessage(
                "1.0", "desktop", "desktop-session", authSessionId, epoch, epoch,
                "2026-07-18T00:00:00Z", userId, tenantId, "platform",
                true, Set.of("lawyer"), Set.of("framework:read"));
    }

    private static InterruptionMetadata metadata() {
        return InterruptionMetadata.builder("hitl", new OverAllState())
                .addToolFeedback(InterruptionMetadata.ToolFeedback.builder()
                        .id("call-1").name("write_file").arguments("{}").description("write").build())
                .build();
    }
}
