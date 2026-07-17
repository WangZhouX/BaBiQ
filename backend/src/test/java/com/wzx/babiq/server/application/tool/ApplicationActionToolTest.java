package com.wzx.babiq.server.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.api.JsonRpcMessage;
import com.wzx.babiq.server.application.action.PendingApplicationAction;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.api.ApplicationActionProtocolHandler;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.ApplicationActionItem;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/** application_action 只使用可信目录、页面快照与拦截器冻结的身份。 */
class ApplicationActionToolTest {

    private final ObjectMapper json = new ObjectMapper();
    private final BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
    private final ApplicationCatalogRegistry catalogs = mock(ApplicationCatalogRegistry.class);
    private final ApplicationPageContextRegistry contexts = mock(ApplicationPageContextRegistry.class);
    private final PendingApplicationActions pending = mock(PendingApplicationActions.class);
    private final ApplicationActionProtocolHandler protocol = mock(ApplicationActionProtocolHandler.class);
    private final ItemEmitter emitter = mock(ItemEmitter.class);
    private final TrustedDesktopConnection connection = new TrustedDesktopConnection(
            "reservation-a", "desktop-a", "desktop-session-a", "websocket-a");
    private final TrustedBusinessIdentity identity = new TrustedBusinessIdentity(
            "reservation-a", "websocket-a", "desktop-a", "desktop-session-a", "auth-a", 7,
            "user-a", "tenant-a", "platform-a", Set.of("lawyer"), Set.of("framework:read"));
    private final BusinessIdentityScope scope = BusinessIdentityScope.scoped(
            "desktop-a", "desktop-session-a", "auth-a", 7, "user-a", "tenant-a", "platform-a");
    private ApplicationActionTool tool;

    @BeforeEach
    void setUp() {
        tool = new ApplicationActionTool(json, scopes, catalogs, contexts, pending, protocol,
                () -> "execution-fixed", () -> 0L);
    }

    @Test
    void validatesCatalogContextAndInputThenSendsOneRequestAndReturnsShortTerminal() throws Exception {
        arrangeSnapshots(catalogPayload(false), contextPayload(7));
        CompletableFuture<PendingApplicationAction> waiter = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<Consumer<PendingApplicationAction>> progress =
                new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(1);
        when(pending.register(eq("execution-fixed"), any(), eq(PendingApplicationAction.Path.READ_ONLY),
                any(), any())).thenAnswer(invocation -> {
                    progress.set(invocation.getArgument(4));
                    registered.countDown();
                    return waiter;
                });
        when(protocol.sendActionRequest(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        ObjectNode terminalPayload = json.createObjectNode()
                .put("output", "secret-result-must-not-be-returned")
                .put("errorMessage", "secret-error-must-not-be-returned");

        ApplicationActionToolResult result;
        {
            CompletableFuture<ApplicationActionToolResult> call = CompletableFuture.supplyAsync(() -> {
                try (ApplicationToolInvocationContext.Scope ignored = invocationScope()) {
                    return invoke("framework.demo", 2, json.createObjectNode().put("query", "safe"), 7);
                }
            });
            registered.await();
            waiter.complete(action(PendingApplicationAction.State.COMPLETED, terminalPayload));
            result = call.join();
        }

        assertThat(result.executionId()).isEqualTo("execution-fixed");
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.errorCode()).isNull();
        assertThat(result.toString()).doesNotContain("secret-result", "secret-error");
        ArgumentCaptor<JsonNode> requestPayload = ArgumentCaptor.forClass(JsonNode.class);
        verify(protocol).sendActionRequest(any(), requestPayload.capture());
        assertThat(requestPayload.getValue().path("actionId").asText()).isEqualTo("framework.demo");
        assertThat(requestPayload.getValue().path("actionVersion").asInt()).isEqualTo(2);
        assertThat(requestPayload.getValue().path("origin").asText()).isEqualTo("agent");
        assertThat(requestPayload.getValue().path("state").asText()).isEqualTo("requested");
        assertThat(requestPayload.getValue().toString()).doesNotContain("auth-a", "tenant-a", "secret");
    }

    @Test
    void rejectsMissingUnknownOrTypeConfusedRiskBeforeRegistrationOrDispatch() {
        java.util.List<JsonNode> invalidRisks = java.util.List.of(
                com.fasterxml.jackson.databind.node.MissingNode.getInstance(),
                json.getNodeFactory().textNode("critical_write"),
                json.createObjectNode().put("level", "high"),
                json.createArrayNode().add("high_risk"));

        for (JsonNode invalidRisk : invalidRisks) {
            ObjectNode catalog = catalogPayload(false);
            ObjectNode descriptor = (ObjectNode) catalog.path("actions").path("framework.demo");
            if (invalidRisk.isMissingNode()) {
                descriptor.remove("risk");
            } else {
                descriptor.set("risk", invalidRisk);
            }
            arrangeSnapshots(catalog, contextPayload(7));

            ApplicationActionToolResult result;
            try (ApplicationToolInvocationContext.Scope ignored = invocationScope()) {
                result = invoke("framework.demo", 2, json.createObjectNode(), 7);
            }

            assertThat(result.errorCode()).isEqualTo("validation_failed");
        }
        verify(pending, never()).register(any(), any(), any(), any(), any());
        verify(protocol, never()).sendActionRequest(any(), any());
    }

    @Test
    void rejectsCoercedCatalogAndPageContextMetadataBeforeRegistrationOrDispatch() {
        java.util.List<Runnable> cases = java.util.List.of(
                () -> {
                    ObjectNode catalog = catalogPayload(false);
                    ((ObjectNode) catalog.path("actions").path("framework.demo")).put("version", "2");
                    arrangeSnapshots(catalog, contextPayload(7));
                    assertValidationFailure("framework.demo", 2, "page-1", 7);
                },
                () -> {
                    ObjectNode catalog = catalogPayload(false);
                    ((ObjectNode) catalog.path("actions").path("framework.demo")).put("version", 2.0);
                    arrangeSnapshots(catalog, contextPayload(7));
                    assertValidationFailure("framework.demo", 2, "page-1", 7);
                },
                () -> {
                    ObjectNode descriptor = (ObjectNode) catalogPayload(false)
                            .path("actions").path("framework.demo").deepCopy();
                    descriptor.put("id", 123);
                    ObjectNode catalog = json.createObjectNode();
                    catalog.putObject("actions").set("123", descriptor);
                    arrangeSnapshots(catalog, contextPayload(7));
                    assertValidationFailure("123", 2, "page-1", 7);
                },
                () -> {
                    ObjectNode context = contextPayload(7);
                    context.put("pageId", 123);
                    arrangeSnapshots(catalogPayload(false), context);
                    assertValidationFailure("framework.demo", 2, "123", 7);
                },
                () -> {
                    ObjectNode context = contextPayload(7);
                    context.put("contextRevision", "7");
                    arrangeSnapshots(catalogPayload(false), context);
                    assertValidationFailure("framework.demo", 2, "page-1", 7);
                },
                () -> {
                    ObjectNode context = contextPayload(7);
                    context.put("contextRevision", 7.0);
                    arrangeSnapshots(catalogPayload(false), context);
                    assertValidationFailure("framework.demo", 2, "page-1", 7);
                });

        cases.forEach(Runnable::run);
        verify(pending, never()).register(any(), any(), any(), any(), any());
        verify(protocol, never()).sendActionRequest(any(), any());
    }

    @Test
    void arrayCatalogRequiresAnExactTextualActionId() {
        ObjectNode descriptor = (ObjectNode) catalogPayload(false)
                .path("actions").path("framework.demo").deepCopy();
        descriptor.remove("id");
        ObjectNode catalog = json.createObjectNode();
        catalog.putArray("actions").add(descriptor);
        arrangeSnapshots(catalog, contextPayload(7));

        ApplicationActionToolResult result;
        try (ApplicationToolInvocationContext.Scope ignored = invocationScope()) {
            result = invoke("framework.demo", 2, json.createObjectNode(), 7);
        }

        assertThat(result.errorCode()).isEqualTo("action_not_found");
        verify(pending, never()).register(any(), any(), any(), any(), any());
        verify(protocol, never()).sendActionRequest(any(), any());
    }

    @Test
    void emitsOneSafeProgressItemAndUpdatesTheSameItemForEveryLifecycleState() throws Exception {
        arrangeSnapshots(catalogPayload(true), contextPayload(7));
        CompletableFuture<PendingApplicationAction> waiter = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<Consumer<PendingApplicationAction>> progress =
                new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(1);
        when(pending.register(eq("execution-fixed"), any(), eq(PendingApplicationAction.Path.HIGH_RISK),
                any(), any())).thenAnswer(invocation -> {
                    progress.set(invocation.getArgument(4));
                    registered.countDown();
                    return waiter;
                });
        when(protocol.sendActionRequest(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        {
            CompletableFuture<ApplicationActionToolResult> call = CompletableFuture.supplyAsync(() -> {
                try (ApplicationToolInvocationContext.Scope ignored = invocationScope()) {
                    return invoke("framework.demo", 2, json.createObjectNode(), 7);
                }
            });
            registered.await();
            Consumer<PendingApplicationAction> listener = progress.get();
            listener.accept(action(PendingApplicationAction.State.ACCEPTED, null));
            listener.accept(action(PendingApplicationAction.State.PREVIEWED,
                    json.createObjectNode().put("previewSummary", "safe preview").put("secret", "hidden")));
            listener.accept(action(PendingApplicationAction.State.APPROVAL_REQUIRED, null));
            listener.accept(action(PendingApplicationAction.State.RUNNING, null));
            PendingApplicationAction terminal = action(PendingApplicationAction.State.REJECTED,
                    json.createObjectNode().put("errorCode", "approval_denied").put("errorMessage", "hidden"));
            listener.accept(terminal);
            waiter.complete(terminal);
            assertThat(call.join().errorCode()).isEqualTo("approval_denied");
        }

        ArgumentCaptor<ApplicationActionItem> added = ArgumentCaptor.forClass(ApplicationActionItem.class);
        ArgumentCaptor<ApplicationActionItem> updated = ArgumentCaptor.forClass(ApplicationActionItem.class);
        verify(emitter).emitApplicationActionAdded(added.capture());
        verify(emitter, org.mockito.Mockito.times(5)).emitApplicationActionUpdated(updated.capture());
        assertThat(updated.getAllValues()).extracting(ApplicationActionItem::status)
                .containsExactly("accepted", "previewed", "approval_required", "running", "rejected");
        assertThat(updated.getAllValues()).extracting(ApplicationActionItem::id)
                .containsOnly(added.getValue().id());
        assertThat(added.getValue().toString()).doesNotContain("auth-a", "tenant-a", "hidden");
    }

    @Test
    void progressSummariesRedactTokenAndApiKeyPatterns() throws Exception {
        arrangeSnapshots(catalogPayload(true), contextPayload(7));
        CompletableFuture<PendingApplicationAction> waiter = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<Consumer<PendingApplicationAction>> progress =
                new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(1);
        when(pending.register(eq("execution-fixed"), any(), any(), any(), any())).thenAnswer(invocation -> {
            progress.set(invocation.getArgument(4));
            registered.countDown();
            return waiter;
        });
        when(protocol.sendActionRequest(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<ApplicationActionToolResult> call = CompletableFuture.supplyAsync(() -> {
            try (ApplicationToolInvocationContext.Scope ignored = invocationScope()) {
                return invoke("framework.demo", 2, json.createObjectNode(), 7);
            }
        });
        registered.await();
        PendingApplicationAction terminal = action(PendingApplicationAction.State.FAILED,
                json.createObjectNode()
                        .put("errorCode", "remote_request_failed")
                        .put("errorSummary", "token=abcdefgh123456 api_key=secretvalue123"));
        progress.get().accept(terminal);
        waiter.complete(terminal);
        call.join();

        ArgumentCaptor<ApplicationActionItem> updated = ArgumentCaptor.forClass(ApplicationActionItem.class);
        verify(emitter).emitApplicationActionUpdated(updated.capture());
        assertThat(updated.getValue().errorSummary())
                .contains("[REDACTED:api-key]")
                .doesNotContain("abcdefgh123456", "secretvalue123");
    }

    @Test
    void plainTextProgressRedactsCredentialAndIdentityScalars() throws Exception {
        arrangeSnapshots(catalogPayload(true), contextPayload(7));
        CompletableFuture<PendingApplicationAction> waiter = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<Consumer<PendingApplicationAction>> progress =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(pending.register(eq("execution-fixed"), any(), any(), any(), any())).thenAnswer(invocation -> {
            progress.set(invocation.getArgument(4));
            return waiter;
        });
        when(protocol.sendActionRequest(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<ApplicationActionToolResult> call = CompletableFuture.supplyAsync(() -> {
            try (ApplicationToolInvocationContext.Scope ignored = invocationScope()) {
                return invoke("framework.demo", 2, json.createObjectNode(), 7);
            }
        });
        while (progress.get() == null) Thread.onSpinWait();
        PendingApplicationAction terminal = action(PendingApplicationAction.State.FAILED,
                json.createObjectNode().put("errorCode", "remote_request_failed")
                        .put("errorSummary", "safe password=pw-secret secret: abc token=token-secret "
                                + "mobile=13800138000 idCard=330102199001011234 bankCard=6222021234567890"));
        progress.get().accept(terminal);
        waiter.complete(terminal);
        call.join();

        ArgumentCaptor<ApplicationActionItem> item = ArgumentCaptor.forClass(ApplicationActionItem.class);
        verify(emitter).emitApplicationActionUpdated(item.capture());
        assertThat(item.getValue().errorSummary())
                .contains("safe", "[REDACTED]")
                .doesNotContain("pw-secret", "abc", "token-secret", "13800138000",
                        "330102199001011234", "6222021234567890");
    }

    @Test
    void rejectsMissingOrStaleTrustedInputsBeforeRegistrationOrSend() {
        assertThat(invoke("framework.demo", 2, json.createObjectNode(), 7).errorCode())
                .isEqualTo("protocol_error");

        try (ApplicationToolInvocationContext.Scope ignored = invocationScope()) {
            when(scopes.resolveActive(scope)).thenReturn(Optional.empty());
            assertThat(invoke("framework.demo", 2, json.createObjectNode(), 7).errorCode())
                    .isEqualTo("auth_expired");

            when(scopes.resolveActive(scope)).thenReturn(Optional.of(active()));
            when(catalogs.current(connection)).thenReturn(Optional.of(
                    new ApplicationCatalogRegistry.CatalogSnapshot(connection, 1, catalogPayload(false), true)));
            when(contexts.current(connection)).thenReturn(Optional.of(
                    new ApplicationPageContextRegistry.PageContextSnapshot(connection, 1, 9, contextPayload(8), true)));
            assertThat(invoke("framework.demo", 2, json.createObjectNode(), 7).errorCode())
                    .isEqualTo("context_stale");

            assertThat(invoke("missing.action", 2, json.createObjectNode(), 8).errorCode())
                    .isEqualTo("action_not_found");
            assertThat(invoke("framework.demo", 99, json.createObjectNode(), 8).errorCode())
                    .isEqualTo("action_not_found");
            assertThat(invoke("framework.demo", 2,
                    json.createObjectNode().put("padding", "x".repeat(65 * 1024)), 8).errorCode())
                    .isEqualTo("validation_failed");
        }

        verify(pending, never()).register(any(), any(), any(), any(), any());
        verify(protocol, never()).sendActionRequest(any(), any());
    }

    @Test
    void mapsEveryTerminalAndErrorCodeWithoutRetryingOutcomeUnknown() {
        for (PendingApplicationAction.State state : Set.of(
                PendingApplicationAction.State.COMPLETED,
                PendingApplicationAction.State.FAILED,
                PendingApplicationAction.State.REJECTED,
                PendingApplicationAction.State.CANCELED,
                PendingApplicationAction.State.EXPIRED,
                PendingApplicationAction.State.OUTCOME_UNKNOWN)) {
            ApplicationActionToolResult result = ApplicationActionTool.toResult(action(state,
                    json.createObjectNode().put("errorCode", state == PendingApplicationAction.State.OUTCOME_UNKNOWN
                            ? "outcome_unknown" : "remote_request_failed")));
            assertThat(result.status()).isEqualTo(state.name().toLowerCase());
        }
    }

    @Test
    void preservesEveryStableDesktopErrorCodeInTheShortResult() {
        assertThat(java.util.Arrays.stream(
                        com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator
                                .ApplicationProtocolErrorCode.values())
                .map(error -> ApplicationActionTool.toResult(action(
                        PendingApplicationAction.State.FAILED,
                        json.createObjectNode().put("errorCode", error.wireName()))).errorCode()))
                .containsExactlyInAnyOrderElementsOf(
                        com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator
                                .ApplicationProtocolErrorCode.wireNames());
    }

    @Test
    void annotatedToolSchemaContainsOnlyActionParametersAndToolContext() throws Exception {
        var method = ApplicationActionTool.class.getMethod(
                "applicationAction", String.class, Integer.class, JsonNode.class,
                String.class, Long.class, ToolContext.class);

        assertThat(method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class).name())
                .isEqualTo("application_action");
        assertThat(method.getParameters()).extracting(java.lang.reflect.Parameter::getType)
                .containsExactly(String.class, Integer.class, JsonNode.class, String.class, Long.class, ToolContext.class);
        assertThat(java.util.Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(org.springframework.ai.tool.annotation.ToolParam.class)))
                .hasSize(5);
    }

    @Test
    void failedAcknowledgementKeepsExecutionForReconciliationAndNeverCancelsOrRetries() {
        arrangeSnapshots(catalogPayload(false), contextPayload(7));
        CompletableFuture<PendingApplicationAction> waiter = new CompletableFuture<>();
        when(pending.register(eq("execution-fixed"), any(), eq(PendingApplicationAction.Path.READ_ONLY),
                any(), any())).thenReturn(waiter);
        when(protocol.sendActionRequest(any(), any())).thenReturn(
                CompletableFuture.failedFuture(new com.wzx.babiq.server.application.api.ActionRequestAcknowledgementUncertain(
                        "ack lost", new java.util.concurrent.TimeoutException("ack lost"))));
        when(pending.acknowledgementUncertain(
                eq("execution-fixed"), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(
                        action(PendingApplicationAction.State.OUTCOME_UNKNOWN,
                                json.createObjectNode().put("errorCode", "outcome_unknown"))));
        ApplicationActionToolResult result;
        try (ApplicationToolInvocationContext.Scope ignored = invocationScope()) {
            result = invoke("framework.demo", 2, json.createObjectNode(), 7);
        }

        assertThat(result.executionId()).isEqualTo("execution-fixed");
        assertThat(result.status()).isEqualTo("outcome_unknown");
        assertThat(result.errorCode()).isEqualTo("outcome_unknown");
        verify(protocol, times(1)).sendActionRequest(any(), any());
        verify(pending, never()).cancel(eq("execution-fixed"), any(), any());
    }

    @Test
    void confirmedNegativeAcknowledgementRejectsRegisteredRequestWithoutReconciliationOrRetry() {
        arrangeSnapshots(catalogPayload(false), contextPayload(7));
        CompletableFuture<PendingApplicationAction> waiter = new CompletableFuture<>();
        when(pending.register(eq("execution-fixed"), any(), eq(PendingApplicationAction.Path.READ_ONLY),
                any(), any())).thenReturn(waiter);
        when(protocol.sendActionRequest(any(), any())).thenReturn(CompletableFuture.failedFuture(
                new com.wzx.babiq.server.application.api.ConfirmedActionRequestRejection(
                        "remote_request_failed", "desktop rejected action request")));
        when(pending.confirmedRequestRejected(
                eq("execution-fixed"), any(), any(), eq("remote_request_failed"), any()))
                .thenReturn(CompletableFuture.completedFuture(action(
                        PendingApplicationAction.State.REJECTED,
                        json.createObjectNode().put("errorCode", "remote_request_failed"))));

        ApplicationActionToolResult result;
        try (ApplicationToolInvocationContext.Scope ignored = invocationScope()) {
            result = invoke("framework.demo", 2, json.createObjectNode(), 7);
        }

        assertThat(result.executionId()).isEqualTo("execution-fixed");
        assertThat(result.status()).isEqualTo("rejected");
        assertThat(result.errorCode()).isEqualTo("remote_request_failed");
        verify(protocol, times(1)).sendActionRequest(any(), any());
        verify(pending, never()).acknowledgementUncertain(any(), any(), any(), any());
    }

    @Test
    void nestedJsonAndInvalidRawProgressNeverExposeSensitiveValues() throws Exception {
        arrangeSnapshots(catalogPayload(true), contextPayload(7));
        CompletableFuture<PendingApplicationAction> waiter = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<Consumer<PendingApplicationAction>> progress =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(pending.register(eq("execution-fixed"), any(), any(), any(), any())).thenAnswer(invocation -> {
            progress.set(invocation.getArgument(4));
            return waiter;
        });
        when(protocol.sendActionRequest(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<ApplicationActionToolResult> call = CompletableFuture.supplyAsync(() -> {
            try (ApplicationToolInvocationContext.Scope ignored = invocationScope()) {
                return invoke("framework.demo", 2, json.createObjectNode(), 7);
            }
        });
        while (progress.get() == null) Thread.onSpinWait();
        String nested = """
                {"message":"safe message 联系人 13800138000 身份证 330102199001011234",
                 "notes":["银行卡 6222021234567890", "Authorization: Bearer scalar-secret-token"],
                 "account":{"password":"pw-secret","id_card":"330102199001011234"},
                 "rows":[{"mobile":"13800138000","bank-card":"6222021234567890"}],
                 "authorization":"Bearer abcdefghijklmnop","refreshToken":"refresh-secret"
                }
                """;
        progress.get().accept(action(PendingApplicationAction.State.PREVIEWED,
                json.createObjectNode().put("previewSummary", nested)));
        PendingApplicationAction terminal = action(PendingApplicationAction.State.FAILED,
                json.createObjectNode().put("errorCode", "remote_request_failed")
                        .put("errorSummary", "{invalid raw mobile=13800138000 token=abcdefgh123456"));
        progress.get().accept(terminal);
        waiter.complete(terminal);
        call.join();

        ArgumentCaptor<ApplicationActionItem> items = ArgumentCaptor.forClass(ApplicationActionItem.class);
        verify(emitter, times(2)).emitApplicationActionUpdated(items.capture());
        String rendered = items.getAllValues().toString();
        assertThat(rendered)
                .contains("safe message", "[REDACTED]")
                .doesNotContain("pw-secret", "330102199001011234", "13800138000",
                        "6222021234567890", "scalar-secret-token", "abcdefghijklmnop",
                        "refresh-secret", "abcdefgh123456")
                .doesNotContain("{invalid raw");
    }

    private void arrangeSnapshots(JsonNode catalog, JsonNode context) {
        when(scopes.resolveActive(scope)).thenReturn(Optional.of(active()));
        when(catalogs.current(connection)).thenReturn(Optional.of(
                new ApplicationCatalogRegistry.CatalogSnapshot(connection, 1, catalog, true)));
        when(contexts.current(connection)).thenReturn(Optional.of(
                new ApplicationPageContextRegistry.PageContextSnapshot(connection, 1, 9, context, true)));
    }

    private ApplicationActionToolResult invoke(String actionId, int version, JsonNode input, long revision) {
        return tool.applicationAction(actionId, version, input, "page-1", revision,
                new ToolContext(Map.of(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter)));
    }

    private void assertValidationFailure(
            String actionId,
            int version,
            String pageId,
            long revision) {
        ApplicationActionToolResult result;
        try (ApplicationToolInvocationContext.Scope ignored = invocationScope()) {
            result = tool.applicationAction(actionId, version, json.createObjectNode(), pageId, revision,
                    new ToolContext(Map.of(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter)));
        }
        assertThat(result.errorCode()).isIn("action_not_found", "context_stale", "validation_failed");
    }

    private ApplicationToolInvocationContext.Scope invocationScope() {
        return ApplicationToolInvocationContext.install(new ApplicationToolInvocationContext.Invocation(
                "tool-call-a", "thread-a", "turn-a", scope));
    }

    private BusinessIdentityScopeService.ActiveBusinessIdentity active() {
        return new BusinessIdentityScopeService.ActiveBusinessIdentity(connection, identity);
    }

    private ObjectNode catalogPayload(boolean highRisk) {
        ObjectNode action = json.createObjectNode()
                .put("id", "framework.demo")
                .put("version", 2)
                .put("title", "Demo action")
                .put("risk", highRisk ? "high_risk" : "read_only")
                .put("enabled", true);
        action.putArray("requiredPermissions").add("framework:read");
        return json.createObjectNode().set("actions", json.createObjectNode().set("framework.demo", action));
    }

    private ObjectNode contextPayload(long revision) {
        return json.createObjectNode().put("pageId", "page-1").put("contextRevision", revision);
    }

    private PendingApplicationAction action(PendingApplicationAction.State state, JsonNode payload) {
        return new PendingApplicationAction(
                "execution-fixed",
                new PendingApplicationAction.Correlation("thread-a", "turn-a", "tool-call-a"),
                state == PendingApplicationAction.State.REJECTED
                        ? PendingApplicationAction.Path.HIGH_RISK : PendingApplicationAction.Path.READ_ONLY,
                state, payload, null, Instant.EPOCH,
                new PendingApplicationAction.ConnectionContext(
                        "reservation-a", "websocket-a", "desktop-a", "desktop-session-a", "auth-a", 7,
                        "user-a", "tenant-a", "platform-a"));
    }

}
