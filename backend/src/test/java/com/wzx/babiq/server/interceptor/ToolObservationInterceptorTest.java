package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BuiltInSubAgents;
import com.wzx.babiq.server.agent.delegation.SubAgentDelegationContext;
import com.wzx.babiq.server.conversation.items.AgentDelegationItem;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.CommandExecutionItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ToolObservationInterceptorTest {

    @Test
    void applicationActionPersistenceStoresOnlySafeMetadataAndNeverRawInput() {
        ToolCallPersistenceService persistence = mock(ToolCallPersistenceService.class);
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(new BaBiQMetrics(), persistence);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_safe", "turn_safe", "provider", "model");
        String arguments = """
                {"actionId":"framework.demo","actionVersion":2,"pageId":"page-1","contextRevision":7,
                 "input":{"password":"pw-secret","person":{"idCard":"330102199001011234"},
                 "contacts":[{"mobile":"13800138000"}]}}
                """;
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("application_action")
                .toolCallId("call_safe")
                .arguments(arguments)
                .context(Map.of(TurnObservationContext.METADATA_KEY, context))
                .build();

        interceptor.interceptToolCall(request,
                ignored -> ToolCallResponse.of("call_safe", "application_action", "ok"));

        ArgumentCaptor<String> persistedArgs = ArgumentCaptor.forClass(String.class);
        verify(persistence).recordStarted(
                eq("call_safe"), eq("thr_safe"), eq("turn_safe"), eq("application_action"),
                persistedArgs.capture(), any(), any(), any(), any(), any());
        assertThat(persistedArgs.getValue())
                .contains("framework.demo", "actionVersion", "page-1", "contextRevision")
                .doesNotContain("pw-secret", "330102199001011234", "13800138000", "password", "idCard", "mobile");
    }

    @Test
    void persistenceUsesFrozenTurnScopeEvenAfterLiveIdentityChanges() {
        ToolCallPersistenceService persistence = mock(ToolCallPersistenceService.class);
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(new BaBiQMetrics(), persistence);
        var frozenScope = com.wzx.babiq.server.application.scope.BusinessIdentityScope.scoped(
                "desktop", "session-a", "auth-a", 1, "user-a", "tenant-a", "platform");
        TurnObservationContext context = TurnObservationContext.start(
                "thr_scope", "turn_scope", "provider", "model", frozenScope);
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("application_action")
                .toolCallId("call_scope")
                .arguments("{\"actionId\":\"demo.read\",\"input\":{}}")
                .context(Map.of(TurnObservationContext.METADATA_KEY, context))
                .build();

        interceptor.interceptToolCall(request,
                ignored -> ToolCallResponse.of("call_scope", "application_action", "ok"));

        verify(persistence).recordStarted(
                eq("call_scope"), eq("thr_scope"), eq("turn_scope"), eq("application_action"),
                anyString(), any(), any(), any(), eq(frozenScope), any());
    }

    @Test
    void applicationActionPersistenceOmitsOversizedIdentifiersWithoutPersistingTheirContents() {
        String actionId = "action-secret-" + "x".repeat(300);
        String pageId = "page-secret-" + "y".repeat(300);

        String persisted = persistedArguments(
                "application_action",
                """
                        {"actionId":"%s","actionVersion":2,"pageId":"%s","contextRevision":7,
                         "input":{"password":"pw-secret"}}
                        """.formatted(actionId, pageId));

        assertThat(persisted)
                .isEqualTo("{\"actionVersion\":2,\"contextRevision\":7,\"input\":\"[REDACTED]\"}")
                .doesNotContain("action-secret", "page-secret", "pw-secret")
                .hasSizeLessThan(100);
    }

    @Test
    void applicationActionPersistenceOmitsMetadataWithUnsafeTypesOrRanges() {
        String[] unsafeArguments = {
                """
                        {"actionId":{"secret":"object-secret"},"pageId":["array-secret"],
                         "actionVersion":"2","contextRevision":"7","input":"input-secret"}
                        """,
                """
                        {"actionId":123,"pageId":null,"actionVersion":2.5,"contextRevision":7.5,
                         "input":"input-secret"}
                        """,
                """
                        {"actionVersion":-1,"contextRevision":-1,"input":"input-secret"}
                        """,
                """
                        {"actionVersion":2147483648,"contextRevision":9223372036854775808,
                         "input":"input-secret"}
                        """
        };

        for (String arguments : unsafeArguments) {
            assertThat(persistedArguments("application_action", arguments))
                    .isEqualTo("{\"input\":\"[REDACTED]\"}");
        }
    }

    @Test
    void applicationActionPersistenceFailsClosedForMalformedJsonAndOmitsUnknownFields() {
        assertThat(persistedArguments("application_action", "{malformed-secret"))
                .isEqualTo("{\"input\":\"[REDACTED]\"}");
        assertThat(persistedArguments(
                "application_action",
                """
                        {"actionId":"framework.demo","actionVersion":2,"pageId":"page-1",
                         "contextRevision":7,"apiKey":"unknown-secret","nested":{"token":"secret"},
                         "input":{"password":"pw-secret"}}
                        """))
                .isEqualTo("{\"actionId\":\"framework.demo\",\"actionVersion\":2,\"pageId\":\"page-1\","
                        + "\"contextRevision\":7,\"input\":\"[REDACTED]\"}");
    }

    @Test
    void ordinaryToolPersistenceKeepsOriginalArgumentsUnchanged() {
        String arguments = "{\"path\":\"README.md\",\"query\":\"ordinary-value\"}";

        assertThat(persistedArguments("read_file", arguments)).isEqualTo(arguments);
    }

    @Test
    void interceptToolCall_should_record_tool_call_in_context_and_metrics() {
        BaBiQMetrics metrics = new BaBiQMetrics();
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(metrics);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_1", "turn_1", "provider-a", "qwen-plus");
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("read_file")
                .toolCallId("call_1")
                .arguments("{}")
                .context(Map.of(TurnObservationContext.METADATA_KEY, context))
                .build();

        ToolCallResponse response = interceptor.interceptToolCall(request,
                ignored -> ToolCallResponse.of("call_1", "read_file", "ok"));

        assertThat(response.getResult()).isEqualTo("ok");
        assertThat(context.toolCalls()).isEqualTo(1);
        assertThat(metrics.snapshot().toolCallsByName()).containsEntry("read_file", 1L);
    }

    @Test
    void interceptToolCall_should_emit_tool_detail_item_before_turn_summary() throws Exception {
        BaBiQMetrics metrics = new BaBiQMetrics();
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(metrics);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_1", "turn_1", "provider-a", "qwen-plus");
        ItemEmitter emitter = mock(ItemEmitter.class);
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("read_file")
                .toolCallId("call_1")
                .arguments("{\"path\":\"README.md\"}")
                .context(Map.of(
                        TurnObservationContext.METADATA_KEY, context,
                        BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter))
                .build();

        ToolCallResponse response = interceptor.interceptToolCall(request,
                ignored -> ToolCallResponse.of("call_1", "read_file", "README content"));

        assertThat(response.getResult()).isEqualTo("README content");
        ArgumentCaptor<ThreadItem> captor = ArgumentCaptor.forClass(ThreadItem.class);
        verify(emitter).emitCommandExecution(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(CommandExecutionItem.class);
        CommandExecutionItem item = (CommandExecutionItem) captor.getValue();
        assertThat(item.command()).contains("read_file").contains("README.md");
        assertThat(item.status()).isEqualTo("completed");
        assertThat(item.stdout()).contains("README content");
    }

    @Test
    void interceptToolCall_should_not_fail_agent_loop_when_persistence_is_unavailable() {
        BaBiQMetrics metrics = new BaBiQMetrics();
        ToolCallPersistenceService persistenceService = mock(ToolCallPersistenceService.class);
        doThrow(new IllegalStateException("foreign key missing in legacy in-memory turn"))
                .when(persistenceService)
                .recordStarted(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(metrics, persistenceService);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_legacy", "turn_legacy", "provider-a", "qwen-plus");
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("read_file")
                .toolCallId("call_legacy")
                .arguments("{}")
                .context(Map.of(TurnObservationContext.METADATA_KEY, context))
                .build();

        ToolCallResponse response = interceptor.interceptToolCall(request,
                ignored -> ToolCallResponse.of("call_legacy", "read_file", "ok"));

        assertThat(response.getResult()).isEqualTo("ok");
        assertThat(context.toolCalls()).isEqualTo(1);
        assertThat(metrics.snapshot().toolCallsByName()).containsEntry("read_file", 1L);
    }

    @Test
    void interceptToolCall_should_fold_child_tool_event_into_delegation_item() throws Exception {
        BaBiQMetrics metrics = new BaBiQMetrics();
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(metrics);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_1", "turn_1", "provider-a", "qwen-plus");
        ItemEmitter emitter = mock(ItemEmitter.class);
        SubAgentDelegationContext delegation = SubAgentDelegationContext.started(
                "it_delegate_1",
                "dlg_1",
                BuiltInSubAgents.MAIN_AGENT_NAME,
                "explorer",
                BabiqAgentMode.READ_ONLY_TOOL,
                emitter,
                context);
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("read_file")
                .toolCallId("call_child_1")
                .arguments("{\"path\":\"README.md\"}")
                .context(Map.of(
                        TurnObservationContext.METADATA_KEY, context,
                        BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter,
                        SubAgentDelegationContext.METADATA_KEY, delegation))
                .build();

        ToolCallResponse response = interceptor.interceptToolCall(request,
                ignored -> ToolCallResponse.of("call_child_1", "read_file", "README content"));

        assertThat(response.getResult()).isEqualTo("README content");
        assertThat(delegation.toolCallCount()).isEqualTo(1);
        verify(emitter, never()).emitCommandExecution(any());
        ArgumentCaptor<ThreadItem> captor = ArgumentCaptor.forClass(ThreadItem.class);
        verify(emitter).emitItemUpdated(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(AgentDelegationItem.class);
        AgentDelegationItem item = (AgentDelegationItem) captor.getValue();
        assertThat(item.delegationId()).isEqualTo("dlg_1");
        assertThat(item.status()).isEqualTo("running");
        assertThat(item.toolCallCount()).isEqualTo(1);
    }

    private String persistedArguments(String toolName, String arguments) {
        ToolCallPersistenceService persistence = mock(ToolCallPersistenceService.class);
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(new BaBiQMetrics(), persistence);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_persist", "turn_persist", "provider", "model");
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName(toolName)
                .toolCallId("call_persist")
                .arguments(arguments)
                .context(Map.of(TurnObservationContext.METADATA_KEY, context))
                .build();

        interceptor.interceptToolCall(request,
                ignored -> ToolCallResponse.of("call_persist", toolName, "ok"));

        ArgumentCaptor<String> persistedArgs = ArgumentCaptor.forClass(String.class);
        verify(persistence).recordStarted(
                eq("call_persist"), eq("thr_persist"), eq("turn_persist"), eq(toolName),
                persistedArgs.capture(), any(), any(), any(), any(), any());
        return persistedArgs.getValue();
    }
}
