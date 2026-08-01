package com.wzx.babiq.server.interceptor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.cloud.ai.graph.agent.interceptor.InterceptorChain;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BuiltInSubAgents;
import com.wzx.babiq.server.agent.delegation.SubAgentDelegationContext;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.items.AgentDelegationItem;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.CommandExecutionItem;
import com.wzx.babiq.server.conversation.items.FileChangeItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ToolObservationInterceptorTest {

    @Test
    void businessAgentToolPersistenceRedactsArgumentsAndSuccessfulResults() {
        ToolCallPersistenceService persistence = mock(ToolCallPersistenceService.class);
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(new BaBiQMetrics(), persistence);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_business", "turn_business", "provider", "model");
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("business_schedule_mutate")
                .toolCallId("call_business")
                .arguments("{\"request\":{\"title\":\"private-client-title\"}}")
                .context(Map.of(TurnObservationContext.METADATA_KEY, context))
                .build();

        interceptor.interceptToolCall(request, ignored -> ToolCallResponse.of(
                "call_business", "business_schedule_mutate",
                "{\"ok\":true,\"data\":{\"title\":\"private-client-title\"}}"));

        verify(persistence).recordStarted(
                eq("call_business"), eq("thr_business"), eq("turn_business"),
                eq("business_schedule_mutate"), eq("{\"arguments\":\"[REDACTED]\"}"),
                any(), any(), any(), any(), any());
        verify(persistence).recordFinished(
                eq("turn_business"), eq("call_business"), eq("completed"),
                eq("{\"result\":\"[REDACTED]\"}"), isNull(), any());
    }

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
    void ordinaryToolPersistenceRedactsOriginalArguments() {
        String arguments = "{\"path\":\"README.md\",\"query\":\"ordinary-value\"}";

        assertThat(persistedArguments("read_file", arguments))
                .isEqualTo("{\"arguments\":\"[REDACTED]\"}")
                .doesNotContain("README.md", "ordinary-value");
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
    void error_tool_response_uses_safe_model_persistence_and_item_diagnostics()
            throws Exception {
        String secretCanary = "tool-error-response-secret-canary-1701";
        ToolCallPersistenceService persistence = mock(ToolCallPersistenceService.class);
        ItemEmitter emitter = mock(ItemEmitter.class);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_error", "turn_error", "provider", "model");
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("read_file")
                .toolCallId("call_error")
                .arguments("{\"path\":\"README.md\"}")
                .context(Map.of(
                        TurnObservationContext.METADATA_KEY, context,
                        BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter))
                .build();

        ToolCallResponse response = interceptor(persistence).interceptToolCall(
                request,
                ignored -> ToolCallResponse.error(
                        "call_error", "read_file", "IO error: " + secretCanary));

        assertThat(response.isError()).isTrue();
        assertThat(response.getToolCallId()).isEqualTo("call_error");
        assertThat(response.getToolName()).isEqualTo("read_file");
        assertThat(response.getResult())
                .isEqualTo("TOOL_EXECUTION_FAILED")
                .doesNotContain(secretCanary);
        ArgumentCaptor<String> persistedError = ArgumentCaptor.forClass(String.class);
        verify(persistence).recordFinished(
                eq("turn_error"), eq("call_error"), eq("failed"),
                isNull(), persistedError.capture(), any());
        assertThat(persistedError.getValue()).isEqualTo("TOOL_EXECUTION_FAILED");
        ArgumentCaptor<ThreadItem> emitted = ArgumentCaptor.forClass(ThreadItem.class);
        verify(emitter).emitCommandExecution(emitted.capture());
        assertThat(emitted.getValue()).isInstanceOf(CommandExecutionItem.class);
        CommandExecutionItem errorItem = (CommandExecutionItem) emitted.getValue();
        assertThat(errorItem.stdout()).isNull();
        assertThat(errorItem.stderr())
                .isEqualTo("TOOL_EXECUTION_FAILED");
    }

    @Test
    @ResourceLock("logback-tool-observation")
    void outer_observation_sanitizes_real_sandbox_denial_for_model_database_items_and_logs(
            @TempDir Path root) throws Exception {
        String secretCanary = "sandbox-denial-secret-canary-1701";
        ToolCallPersistenceService persistence = mock(ToolCallPersistenceService.class);
        ItemEmitter emitter = mock(ItemEmitter.class);
        doThrow(new IllegalStateException("file item failed " + secretCanary))
                .when(emitter).emitFileChange(any(FileChangeItem.class));
        TurnObservationContext context = TurnObservationContext.start(
                "thr_sandbox", "turn_sandbox", "provider", "model");
        String blockedPath = root.resolve(secretCanary + ".txt").toString();
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("write_file")
                .toolCallId("call_sandbox")
                .arguments("{\"path\":\"" + blockedPath.replace("\\", "\\\\")
                        + "\",\"content\":\"safe\"}")
                .context(Map.of(
                        TurnObservationContext.METADATA_KEY, context,
                        BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter,
                        BaBiQSandboxInterceptor.CONTEXT_CWD, root.toString(),
                        BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.READ_ONLY.name()))
                .build();
        AgentLoopProperties properties = new AgentLoopProperties(
                20,
                ApprovalPolicy.ON_REQUEST,
                SandboxMode.WORKSPACE_WRITE,
                List.of(),
                new AgentLoopProperties.Tools(new AgentLoopProperties.Output(4000)));
        BaBiQSandboxInterceptor sandbox =
                new BaBiQSandboxInterceptor(properties, new ConversationService());
        ToolObservationInterceptor observation = interceptor(persistence);

        Logger logger = (Logger) LoggerFactory.getLogger(BaBiQSandboxInterceptor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        ToolCallResponse response;
        try {
            response = InterceptorChain.chainToolInterceptors(
                    List.of(observation, sandbox),
                    ignored -> {
                        throw new AssertionError("sandbox denial must not invoke the real tool");
                    }).call(request);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }

        assertThat(response.isError()).isTrue();
        assertThat(response.getToolCallId()).isEqualTo("call_sandbox");
        assertThat(response.getToolName()).isEqualTo("write_file");
        assertThat(response.getResult())
                .isEqualTo("TOOL_EXECUTION_FAILED")
                .doesNotContain(secretCanary);
        ArgumentCaptor<String> persistedArguments = ArgumentCaptor.forClass(String.class);
        verify(persistence).recordStarted(
                eq("call_sandbox"), eq("thr_sandbox"), eq("turn_sandbox"), eq("write_file"),
                persistedArguments.capture(), anyString(), any(), any(), any(), any());
        assertThat(persistedArguments.getValue())
                .isEqualTo("{\"arguments\":\"[REDACTED]\"}")
                .doesNotContain(secretCanary);
        verify(persistence).recordFinished(
                eq("turn_sandbox"), eq("call_sandbox"), eq("denied"),
                isNull(), eq("TOOL_EXECUTION_FAILED"), any());

        ArgumentCaptor<ThreadItem> commandItem = ArgumentCaptor.forClass(ThreadItem.class);
        verify(emitter).emitCommandExecution(commandItem.capture());
        assertThat(commandItem.getValue()).isInstanceOf(CommandExecutionItem.class);
        CommandExecutionItem deniedCommand = (CommandExecutionItem) commandItem.getValue();
        assertThat(deniedCommand.command())
                .isEqualTo("write_file")
                .doesNotContain(secretCanary);
        assertThat(deniedCommand.stderr())
                .isEqualTo("TOOL_EXECUTION_FAILED");
        ArgumentCaptor<FileChangeItem> fileItem = ArgumentCaptor.forClass(FileChangeItem.class);
        verify(emitter).emitFileChange(fileItem.capture());
        assertThat(fileItem.getValue().path()).isEqualTo("[REDACTED]");
        assertThat(fileItem.getValue().contentPreview())
                .isEqualTo("TOOL_EXECUTION_FAILED")
                .doesNotContain(secretCanary);

        String logs = appender.list.stream()
                .map(event -> event.getFormattedMessage() + "\n" +
                        (event.getThrowableProxy() == null
                                ? ""
                                : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(logs).doesNotContain(secretCanary);
    }

    @Test
    @ResourceLock("logback-tool-observation")
    void failed_tool_diagnostics_do_not_persist_emit_or_log_exception_secrets() throws Exception {
        String secretCanary = "tool-secret-canary-1701";
        String startCanary = "tool-start-secret-canary-1701";
        String persistenceCanary = "tool-persistence-secret-canary-1701";
        String emitterCanary = "tool-emitter-secret-canary-1701";
        ToolCallPersistenceService persistence = mock(ToolCallPersistenceService.class);
        doThrow(new IllegalStateException("start failed " + startCanary))
                .when(persistence).recordStarted(
                        eq("call_secret"), eq("thr_secret"), eq("turn_secret"), eq("read_file"),
                        anyString(), anyString(), any(), any(), any(), any());
        doThrow(new IllegalStateException("persistence failed " + persistenceCanary))
                .when(persistence).recordFinished(
                        eq("turn_secret"), eq("call_secret"), eq("failed"),
                        any(), anyString(), any());
        ItemEmitter emitter = mock(ItemEmitter.class);
        doThrow(new IllegalStateException("emit failed " + emitterCanary))
                .when(emitter).emitCommandExecution(any());
        TurnObservationContext context = TurnObservationContext.start(
                "thr_secret", "turn_secret", "provider", "model");
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("read_file")
                .toolCallId("call_secret")
                .arguments("{\"path\":\"README.md\"}")
                .context(Map.of(
                        TurnObservationContext.METADATA_KEY, context,
                        BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter))
                .build();
        RuntimeException toolFailure = new IllegalStateException("tool failed " + secretCanary);
        Logger logger = (Logger) LoggerFactory.getLogger(ToolObservationInterceptor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> interceptor(persistence).interceptToolCall(
                    request, ignored -> { throw toolFailure; }))
                    .isSameAs(toolFailure);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }

        ArgumentCaptor<String> persistedError = ArgumentCaptor.forClass(String.class);
        verify(persistence).recordFinished(
                eq("turn_secret"), eq("call_secret"), eq("failed"),
                isNull(), persistedError.capture(), any());
        assertThat(persistedError.getValue()).doesNotContain(secretCanary);

        ArgumentCaptor<ThreadItem> emitted = ArgumentCaptor.forClass(ThreadItem.class);
        verify(emitter).emitCommandExecution(emitted.capture());
        assertThat(emitted.getValue()).isInstanceOf(CommandExecutionItem.class);
        CommandExecutionItem failedItem = (CommandExecutionItem) emitted.getValue();
        assertThat(failedItem.stdout()).isNull();
        assertThat(failedItem.stderr())
                .isEqualTo("TOOL_EXECUTION_FAILED");

        String logs = appender.list.stream()
                .map(event -> event.getFormattedMessage() + "\n" +
                        (event.getThrowableProxy() == null
                                ? ""
                                : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(logs).doesNotContain(
                secretCanary, startCanary, persistenceCanary, emitterCanary);
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

    private ToolObservationInterceptor interceptor(ToolCallPersistenceService persistence) {
        return new ToolObservationInterceptor(new BaBiQMetrics(), persistence);
    }
}
