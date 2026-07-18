package com.wzx.babiq.server.api.method;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.PendingApprovals;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.approval.ApprovalRuleService;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.persistence.service.ApprovalPersistenceService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApprovalRespondHandler 测试。
 *
 * <p>覆盖 D23 的手动 ToolFeedback.Builder 构造和 TurnExecutor.resume 提交流程。</p>
 */
class ApprovalRespondHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handle_builds_approved_feedback_and_submits_resume() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        Turn turn = conversationService.startTurn(thread.id());
        turn.start();
        turn.waitApproval();
        PendingApprovals pendingApprovals = new PendingApprovals();
        pendingApprovals.put(thread.id(), metadata());
        TurnExecutor executor = mock(TurnExecutor.class);
        BaBiQMetrics metrics = new BaBiQMetrics();
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                pendingApprovals, conversationService, objectMapper, executor, metrics);

        Object payload = handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(),
                "turnId", turn.id(),
                "decision", "approve")), null);

        assertThat(((Map<?, ?>) payload).get("delivered")).isEqualTo(true);
        assertThat(turn.status()).isEqualTo(TurnStatus.RUNNING);
        assertThat(metrics.snapshot().approvalDecisionsByDecision()).containsEntry("approved", 1L);
        verify(executor).submitResume(eq(turn), any(InterruptionMetadata.class), eq("."), any(), any());
    }

    @Test
    void build_feedback_maps_edit_to_edited_arguments() {
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                new PendingApprovals(), new ConversationService(), objectMapper, mock(TurnExecutor.class),
                new BaBiQMetrics());

        InterruptionMetadata feedback = handler.buildFeedback(metadata(), "edit", "{\"path\":\"b.txt\"}");

        InterruptionMetadata.ToolFeedback toolFeedback = feedback.toolFeedbacks().get(0);
        assertThat(toolFeedback.getResult()).isEqualTo(InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED);
        assertThat(toolFeedback.getArguments()).isEqualTo("{\"path\":\"b.txt\"}");
    }

    @Test
    void handle_always_records_session_rule_and_resumes_as_approved() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        Turn turn = conversationService.startTurn(thread.id());
        turn.start();
        turn.waitApproval();
        PendingApprovals pendingApprovals = new PendingApprovals();
        pendingApprovals.put(thread.id(), metadata());
        TurnExecutor executor = mock(TurnExecutor.class);
        ApprovalRuleService approvalRuleService = mock(ApprovalRuleService.class);
        BaBiQMetrics metrics = new BaBiQMetrics();
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                pendingApprovals, conversationService, objectMapper, executor, metrics, null, approvalRuleService);

        Object payload = handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(),
                "turnId", turn.id(),
                "decision", "always",
                "scope", "session")), null);

        assertThat(((Map<?, ?>) payload).get("delivered")).isEqualTo(true);
        assertThat(metrics.snapshot().approvalDecisionsByDecision()).containsEntry("always", 1L);
        verify(approvalRuleService).rememberAlways(thread.id(), "write_file", "{\"path\":\"a.txt\"}", "session");
        verify(executor).submitResume(eq(turn), any(InterruptionMetadata.class), eq("."), any(), any());
    }

    @Test
    void businessApprovalResponseRevalidatesFrozenScopeBeforeConsumingOrResuming() {
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "session", "auth", 1, "user", "tenant", "platform");
        PendingApprovals pendingApprovals = new PendingApprovals();
        pendingApprovals.put("thread-a", metadata());
        BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
        when(scopes.resolve(null)).thenReturn(scope);
        when(scopes.withActiveConnectionScope(eq(scope), any())).thenReturn(Optional.empty());
        TurnExecutor executor = mock(TurnExecutor.class);
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                pendingApprovals, mock(ConversationService.class), objectMapper, executor,
                new BaBiQMetrics(), null, null, null, null, null, scopes);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", "thread-a", "turnId", "turn-a", "decision", "approve")), null))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);

        assertThat(pendingApprovals.peek("thread-a")).isNotNull();
        verify(scopes).withActiveConnectionScope(eq(scope), any());
        verify(executor, never()).submitResume(any(), any(), any(), any(), any());
    }

    @Test
    void invalidDecisionIsRejectedBeforeTheTurnOrPendingApprovalIsMutated() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        Turn turn = conversationService.startTurn(thread.id());
        turn.start();
        turn.waitApproval();
        PendingApprovals pendingApprovals = new PendingApprovals();
        InterruptionMetadata original = metadata();
        pendingApprovals.put(thread.id(), original);
        TurnExecutor executor = mock(TurnExecutor.class);
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                pendingApprovals, conversationService, objectMapper, executor, new BaBiQMetrics());

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(), "turnId", turn.id(), "decision", "secret-invalid")), null))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);

        assertThat(turn.status()).isEqualTo(TurnStatus.WAITING_APPROVAL);
        assertThat(pendingApprovals.peek(thread.id())).isSameAs(original);
        verify(executor, never()).submitResume(any(), any(), any(), any(), any());
    }

    @Test
    void unsupportedAlwaysRuleIsRejectedBeforeTheTurnOrPendingApprovalIsMutated() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        Turn turn = conversationService.startTurn(thread.id());
        turn.start();
        turn.waitApproval();
        PendingApprovals pendingApprovals = new PendingApprovals();
        InterruptionMetadata original = metadata();
        pendingApprovals.put(thread.id(), original);
        TurnExecutor executor = mock(TurnExecutor.class);
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                pendingApprovals, conversationService, objectMapper, executor, new BaBiQMetrics());

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(), "turnId", turn.id(), "decision", "always")), null))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);

        assertThat(turn.status()).isEqualTo(TurnStatus.WAITING_APPROVAL);
        assertThat(pendingApprovals.peek(thread.id())).isSameAs(original);
        verify(executor, never()).submitResume(any(), any(), any(), any(), any());
    }

    @Test
    void synchronousResumeRejectionFailsTheTurnAndConsumesTheDurableDecision() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        Turn turn = conversationService.startTurn(thread.id());
        turn.start();
        turn.waitApproval();
        PendingApprovals pendingApprovals = new PendingApprovals();
        pendingApprovals.put(thread.id(), metadata());
        TurnExecutor executor = mock(TurnExecutor.class);
        doThrow(new RejectedExecutionException("secret resume payload"))
                .when(executor).submitResume(any(), any(), any(), any(), any());
        ApprovalPersistenceService approvalPersistence = mock(ApprovalPersistenceService.class);
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                pendingApprovals, conversationService, objectMapper, executor, new BaBiQMetrics(),
                null, null, approvalPersistence, null, null, null);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(), "turnId", turn.id(), "decision", "approve")), null))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);

        assertThat(turn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(turn.failureReason()).isEqualTo("resume_submission_failed");
        assertThat(pendingApprovals.peek(thread.id())).isNull();
        verify(approvalPersistence).resolvePending(eq(thread.id()), eq(turn.id()), eq("approved"),
                eq(null), eq(null), any());
        org.mockito.InOrder order = inOrder(approvalPersistence, executor);
        order.verify(approvalPersistence).resolvePending(eq(thread.id()), eq(turn.id()), eq("approved"),
                eq(null), eq(null), any());
        order.verify(executor).submitResume(eq(turn), any(), eq("."), any(), any());
    }

    @Test
    void approvalPersistenceFailureAfterClaimFailsTheTurnAndDoesNotSubmitResume() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        Turn turn = conversationService.startTurn(thread.id());
        turn.start();
        turn.waitApproval();
        PendingApprovals pendingApprovals = new PendingApprovals();
        pendingApprovals.put(thread.id(), metadata());
        ApprovalPersistenceService approvalPersistence = mock(ApprovalPersistenceService.class);
        doThrow(new IllegalStateException("secret approval SQL path")).when(approvalPersistence)
                .resolvePending(eq(thread.id()), eq(turn.id()), eq("approved"), eq(null), eq(null), any());
        TurnExecutor executor = mock(TurnExecutor.class);
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                pendingApprovals, conversationService, objectMapper, executor, new BaBiQMetrics(),
                null, null, approvalPersistence, null, null, null);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(), "turnId", turn.id(), "decision", "approve")), null))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);

        assertThat(turn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(turn.failureReason()).isEqualTo("resume_submission_failed");
        assertThat(pendingApprovals.peek(thread.id())).isNull();
        verify(executor, never()).submitResume(any(), any(), any(), any(), any());
    }

    @Test
    void runPolicyLookupFailureHappensBeforeClaimWithoutLeakingDetailsAndLeavesApprovalRetryable() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        Turn turn = conversationService.startTurn(thread.id());
        turn.start();
        turn.waitApproval();
        PendingApprovals pendingApprovals = new PendingApprovals();
        InterruptionMetadata original = metadata();
        pendingApprovals.put(thread.id(), original);
        TurnPersistenceService turnPersistence = mock(TurnPersistenceService.class);
        when(turnPersistence.findTurn(turn.id())).thenThrow(new IllegalStateException("secret policy SQL path"));
        TurnExecutor executor = mock(TurnExecutor.class);
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                pendingApprovals, conversationService, objectMapper, executor, new BaBiQMetrics(),
                null, null, null, turnPersistence, null, null);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(), "turnId", turn.id(), "decision", "approve")), null))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class)
                .hasMessage("审批恢复准备失败")
                .hasMessageNotContaining("secret policy SQL path");

        assertThat(turn.status()).isEqualTo(TurnStatus.WAITING_APPROVAL);
        assertThat(pendingApprovals.peek(thread.id())).isSameAs(original);
        verify(executor, never()).submitResume(any(), any(), any(), any(), any());
    }

    @Test
    void staleResponseClaimFailureDoesNotDeleteAReplacementApproval() throws Exception {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        Turn oldTurn = waitingTurn(conversationService, thread);
        Turn replacementTurn = waitingTurn(conversationService, thread);
        PendingApprovals pendingApprovals = spy(new PendingApprovals());
        InterruptionMetadata oldApproval = metadata("old-call");
        InterruptionMetadata replacement = metadata("replacement-call");
        pendingApprovals.put(thread.id(), oldApproval);
        CountDownLatch claimReached = new CountDownLatch(1);
        CountDownLatch continueClaim = new CountDownLatch(1);
        doAnswer(invocation -> {
            claimReached.countDown();
            assertThat(continueClaim.await(5, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(pendingApprovals).claim(thread.id(), oldApproval);
        TurnExecutor executor = mock(TurnExecutor.class);
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                pendingApprovals, conversationService, objectMapper, executor, new BaBiQMetrics());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        java.lang.Thread response = new java.lang.Thread(() -> {
            try {
                handler.handle(objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(), "turnId", oldTurn.id(), "decision", "approve")), null);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        response.start();
        assertThat(claimReached.await(5, TimeUnit.SECONDS)).isTrue();
        pendingApprovals.put(thread.id(), replacement);
        continueClaim.countDown();
        response.join(5000);

        assertThat(response.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);
        assertThat(oldTurn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(replacementTurn.status()).isEqualTo(TurnStatus.WAITING_APPROVAL);
        assertThat(pendingApprovals.peek(thread.id())).isSameAs(replacement);
        verify(executor, never()).submitResume(any(), any(), any(), any(), any());
    }

    @Test
    void postClaimFailureDoesNotDeleteAReplacementApproval() throws Exception {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        Turn oldTurn = waitingTurn(conversationService, thread);
        Turn replacementTurn = waitingTurn(conversationService, thread);
        PendingApprovals pendingApprovals = new PendingApprovals();
        InterruptionMetadata oldApproval = metadata("old-call");
        InterruptionMetadata replacement = metadata("replacement-call");
        pendingApprovals.put(thread.id(), oldApproval);
        CountDownLatch persistenceReached = new CountDownLatch(1);
        CountDownLatch continuePersistence = new CountDownLatch(1);
        ApprovalPersistenceService approvalPersistence = mock(ApprovalPersistenceService.class);
        doAnswer(invocation -> {
            persistenceReached.countDown();
            assertThat(continuePersistence.await(5, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("secret persistence failure");
        }).when(approvalPersistence).resolvePending(
                eq(thread.id()), eq(oldTurn.id()), eq("approved"), eq(null), eq(null), any());
        TurnExecutor executor = mock(TurnExecutor.class);
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                pendingApprovals, conversationService, objectMapper, executor, new BaBiQMetrics(),
                null, null, approvalPersistence, null, null, null);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        java.lang.Thread response = new java.lang.Thread(() -> {
            try {
                handler.handle(objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(), "turnId", oldTurn.id(), "decision", "approve")), null);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        response.start();
        assertThat(persistenceReached.await(5, TimeUnit.SECONDS)).isTrue();
        pendingApprovals.put(thread.id(), replacement);
        continuePersistence.countDown();
        response.join(5000);

        assertThat(response.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);
        assertThat(oldTurn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(replacementTurn.status()).isEqualTo(TurnStatus.WAITING_APPROVAL);
        assertThat(pendingApprovals.peek(thread.id())).isSameAs(replacement);
        verify(executor, never()).submitResume(any(), any(), any(), any(), any());
    }

    private Turn waitingTurn(ConversationService conversationService, Thread thread) {
        Turn turn = conversationService.startTurn(thread.id());
        turn.start();
        turn.waitApproval();
        return turn;
    }

    private InterruptionMetadata metadata() {
        return metadata("call_1");
    }

    private InterruptionMetadata metadata(String callId) {
        InterruptionMetadata.ToolFeedback feedback = InterruptionMetadata.ToolFeedback.builder()
                .id(callId)
                .name("write_file")
                .arguments("{\"path\":\"a.txt\"}")
                .description("写文件")
                .build();
        return InterruptionMetadata.builder("hitl", new OverAllState())
                .addToolFeedback(feedback)
                .build();
    }
}
