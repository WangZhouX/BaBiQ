package com.wzx.babiq.server.context.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.application.context.ApplicationContextModelContributor;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.context.CapabilityCatalogAssembler;
import com.wzx.babiq.server.context.ContextAssembler;
import com.wzx.babiq.server.context.repository.ContextSnapshotRecord;
import com.wzx.babiq.server.context.repository.ContextSnapshotRepository;
import com.wzx.babiq.server.context.repository.ContextWindowRecord;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-2 ContextWindowRuntime 单元测试。
 *
 * <p>运行时必须把历史读取、能力摘要、模型输入渲染和快照落库串起来，同时不能把本轮临时
 * context prompt 写回聊天历史。</p>
 */
class ContextWindowRuntimeTest {

    @Test
    @DisplayName("prepare 会过滤当前 turn 原始 item、写入快照并返回临时模型输入")
    void prepare_should_create_snapshot_and_render_transient_model_input() {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextSnapshotRepository snapshotRepository = mock(ContextSnapshotRepository.class);
        ContextWindowRuntime runtime = new ContextWindowRuntime(
                conversationRepository,
                new ContextAssembler(new ObjectMapper(), text -> text == null ? 0 : 1),
                new CapabilityCatalogAssembler(),
                new ContextualPromptRenderer(),
                windowRepository,
                snapshotRepository,
                new ObjectMapper());
        Instant now = Instant.now();
        when(conversationRepository.listItems("thr_ctx", 200)).thenReturn(List.of(
                ItemRecord.of("it_old", "thr_ctx", "turn_old", "userMessage", 1,
                        "{\"id\":\"it_old\",\"type\":\"userMessage\",\"text\":\"旧问题\"}", "completed", now),
                ItemRecord.of("it_current", "thr_ctx", "turn_current", "userMessage", 2,
                        "{\"id\":\"it_current\",\"type\":\"userMessage\",\"text\":\"本轮原文\"}", "completed", now)));
        when(windowRepository.findByThreadId("thr_ctx")).thenReturn(Optional.empty());
        when(windowRepository.upsert(any(ContextWindowRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ContextWindowRuntimeResult result = runtime.prepare(new ContextWindowRuntimeInput(
                "thr_ctx",
                "turn_current",
                "本轮原文",
                "deepseek",
                "deepseek-v4-pro",
                "E:\\BaBiQ",
                "BaBiQ",
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST),
                128_000,
                new org.springframework.ai.tool.ToolCallback[0]));

        assertThat(result.modelInputText())
                .contains("## Runtime Context")
                .contains("旧问题")
                .contains("本轮原文");
        assertThat(result.snapshotId()).startsWith("ctxsnap_");
        verify(snapshotRepository).save(any(ContextSnapshotRecord.class));
        verify(windowRepository).upsert(any(ContextWindowRecord.class));
    }

    @Test
    @DisplayName("prepare 在快照落库失败时会保留临时上下文并降级为无快照结果")
    void prepare_should_keep_model_input_when_snapshot_persistence_fails() {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextSnapshotRepository snapshotRepository = mock(ContextSnapshotRepository.class);
        ContextWindowRuntime runtime = new ContextWindowRuntime(
                conversationRepository,
                new ContextAssembler(new ObjectMapper(), text -> text == null ? 0 : 1),
                new CapabilityCatalogAssembler(),
                new ContextualPromptRenderer(),
                windowRepository,
                snapshotRepository,
                new ObjectMapper());
        when(conversationRepository.listItems("thr_missing", 200)).thenReturn(List.of());
        when(windowRepository.findByThreadId("thr_missing")).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("missing parent"))
                .when(snapshotRepository).save(any(ContextSnapshotRecord.class));

        ContextWindowRuntimeResult result = runtime.prepare(new ContextWindowRuntimeInput(
                "thr_missing",
                "turn_missing",
                "new request",
                "deepseek",
                "deepseek-v4-pro",
                "E:\\BaBiQ",
                "BaBiQ",
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST),
                128_000,
                new org.springframework.ai.tool.ToolCallback[0]));

        assertThat(result.snapshotId()).isNull();
        assertThat(result.modelInputText())
                .contains("## Runtime Context")
                .contains("new request");
        verify(windowRepository, never()).upsert(any(ContextWindowRecord.class));
    }

    @Test
    @DisplayName("recordUsage 会把真实 prompt token 回填到快照")
    void record_usage_should_write_actual_prompt_tokens() {
        ContextSnapshotRepository snapshotRepository = mock(ContextSnapshotRepository.class);
        ContextWindowRuntime runtime = new ContextWindowRuntime(
                mock(ConversationRepository.class),
                new ContextAssembler(new ObjectMapper()),
                new CapabilityCatalogAssembler(),
                new ContextualPromptRenderer(),
                mock(ContextWindowRepository.class),
                snapshotRepository,
                new ObjectMapper());
        TurnObservationContext context = TurnObservationContext.start("thr_ctx", "turn_ctx", "provider", "model");
        context.recordTokens(42, 0);

        runtime.recordUsage("ctxsnap_1", context);

        verify(snapshotRepository).updateActualPromptTokens(
                "ctxsnap_1", 42L, BusinessIdentityScope.UNSCOPED);
    }

    @Test
    @DisplayName("业务上下文在快照前加入 workspace facts 且不读取长期记忆")
    void business_context_should_use_frozen_scope_contribution_and_skip_long_term_memory() throws Exception {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextSnapshotRepository snapshotRepository = mock(ContextSnapshotRepository.class);
        com.wzx.babiq.server.memory.LongTermMemoryReadService memory =
                mock(com.wzx.babiq.server.memory.LongTermMemoryReadService.class);
        ApplicationContextModelContributor contributor = mock(ApplicationContextModelContributor.class);
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop-a", "desktop-session-a", "auth-a", 1,
                "user-a", "tenant-a", "platform-a");
        when(contributor.contribute(scope)).thenReturn(List.of(
                "<untrusted-data source=\"business_application\">safe page facts</untrusted-data>"));
        when(conversationRepository.listItems("thr_business", 200)).thenReturn(List.of(
                ItemRecord.of("it_action", "thr_business", "turn_old", "applicationAction", 1,
                        new ObjectMapper().writeValueAsString(new com.wzx.babiq.server.conversation.items.ApplicationActionItem(
                                "it_action", "applicationAction", "execution-secret", "demo.read", "Read",
                                "read_only", "completed", null, null, null, 1L)),
                        "completed", Instant.now())));
        when(windowRepository.findByThreadId("thr_business", scope)).thenReturn(Optional.empty());
        when(windowRepository.upsert(any(ContextWindowRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ObjectMapper mapper = new ObjectMapper();
        ContextWindowRuntime runtime = new ContextWindowRuntime(
                conversationRepository, new ContextAssembler(mapper), new CapabilityCatalogAssembler(),
                new ContextualPromptRenderer(), windowRepository, snapshotRepository, mapper,
                null, null, memory, providerOf(contributor));

        ContextWindowRuntimeResult result = runtime.prepare(new ContextWindowRuntimeInput(
                "thr_business", "turn_business", "read page", "provider", "model", "E:\\BaBiQ", "BaBiQ",
                AgentRunPolicy.of(SandboxMode.READ_ONLY, ApprovalPolicy.NEVER), 128_000,
                new org.springframework.ai.tool.ToolCallback[0], null, scope));

        assertThat(result.modelInputText())
                .contains("safe page facts", "business_application")
                .doesNotContain("execution-secret");
        assertThat(result.assemblyResult().envelope().longTermMemory().memoryRefs()).isEmpty();
        assertThat(result.assemblyResult().snapshot().items())
                .anySatisfy(item -> assertThat(item.reason()).isEqualTo("APPLICATION_ACTION_DISPLAY_ONLY"));
        var windowCaptor = org.mockito.ArgumentCaptor.forClass(ContextWindowRecord.class);
        var snapshotCaptor = org.mockito.ArgumentCaptor.forClass(ContextSnapshotRecord.class);
        verify(windowRepository).findByThreadId("thr_business", scope);
        verify(windowRepository).upsert(windowCaptor.capture());
        verify(snapshotRepository).save(snapshotCaptor.capture());
        assertThat(windowCaptor.getValue().businessIdentityScope()).isEqualTo(scope);
        assertThat(snapshotCaptor.getValue().businessIdentityScope()).isEqualTo(scope);
        verify(contributor).contribute(scope);
        verify(memory, never()).readForTurn(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<T> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
