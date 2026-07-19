package com.wzx.babiq.server.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.application.action.PendingApplicationAction;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.api.ApplicationActionProtocolHandler;
import com.wzx.babiq.server.application.api.ConfirmedActionRequestRejection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.ApplicationActionItem;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.memory.redaction.MemorySecretRedactor;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import com.wzx.babiq.server.tool.Tool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Agent 调用业务桌面动作的唯一工具入口。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class ApplicationActionTool implements Tool {

    private static final String TYPE = "applicationAction";
    private static final int SAFE_SUMMARY_LIMIT = 240;
    private final ObjectMapper json;
    private final BusinessIdentityScopeService scopes;
    private final ApplicationCatalogRegistry catalogs;
    private final ApplicationPageContextRegistry contexts;
    private final PendingApplicationActions pending;
    private final ApplicationActionProtocolHandler protocol;
    private final Supplier<String> executionIds;
    private final LongSupplier nanoTime;
    private final MemorySecretRedactor secretRedactor;
    private final ApplicationActionSafeSummary safeSummary;
    private final ToolCallPersistenceService toolCalls;

    @Autowired
    public ApplicationActionTool(
            ObjectMapper json,
            BusinessIdentityScopeService scopes,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            PendingApplicationActions pending,
            ApplicationActionProtocolHandler protocol,
            ToolCallPersistenceService toolCalls) {
        this(json, scopes, catalogs, contexts, pending, protocol,
                () -> "execution-" + UUID.randomUUID(), System::nanoTime, new MemorySecretRedactor(), toolCalls);
    }

    ApplicationActionTool(
            ObjectMapper json,
            BusinessIdentityScopeService scopes,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            PendingApplicationActions pending,
            ApplicationActionProtocolHandler protocol,
            Supplier<String> executionIds,
            LongSupplier nanoTime) {
        this(json, scopes, catalogs, contexts, pending, protocol, executionIds, nanoTime,
                new MemorySecretRedactor(), null);
    }

    ApplicationActionTool(
            ObjectMapper json,
            BusinessIdentityScopeService scopes,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            PendingApplicationActions pending,
            ApplicationActionProtocolHandler protocol,
            Supplier<String> executionIds,
            LongSupplier nanoTime,
            MemorySecretRedactor secretRedactor) {
        this(json, scopes, catalogs, contexts, pending, protocol, executionIds, nanoTime, secretRedactor, null);
    }

    ApplicationActionTool(
            ObjectMapper json,
            BusinessIdentityScopeService scopes,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            PendingApplicationActions pending,
            ApplicationActionProtocolHandler protocol,
            Supplier<String> executionIds,
            LongSupplier nanoTime,
            MemorySecretRedactor secretRedactor,
            ToolCallPersistenceService toolCalls) {
        this.json = json;
        this.scopes = scopes;
        this.catalogs = catalogs;
        this.contexts = contexts;
        this.pending = pending;
        this.protocol = protocol;
        this.executionIds = executionIds;
        this.nanoTime = nanoTime;
        this.secretRedactor = secretRedactor;
        this.safeSummary = new ApplicationActionSafeSummary(json, secretRedactor, SAFE_SUMMARY_LIMIT);
        this.toolCalls = toolCalls;
    }

    @Override
    public String name() {
        return "application_action";
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "application_action",
            description = "Executes one permission-filtered action in the embedded business desktop and waits for its terminal result.")
    public ApplicationActionToolResult applicationAction(
            @ToolParam(description = "稳定动作 ID") String actionId,
            @ToolParam(description = "动作契约版本") Integer actionVersion,
            @ToolParam(description = "动作 JSON 输入") JsonNode input,
            @ToolParam(description = "当前页面 ID") String pageId,
            @ToolParam(description = "当前页面上下文版本") Long contextRevision,
            ToolContext toolContext) {
        ApplicationToolInvocationContext.Invocation invocation =
                ApplicationToolInvocationContext.current().orElse(null);
        if (invocation == null) {
            return failure("protocol_error", "missing immutable application turn scope");
        }
        ItemEmitter emitter = emitter(toolContext);
        Optional<ActionPreparation> atomicPreparation = scopes.withActiveConnectionScope(
                invocation.businessIdentityScope(), trusted -> prepareAction(
                        trusted, invocation, actionId, actionVersion, input, pageId, contextRevision, emitter));
        if (atomicPreparation.isEmpty()) {
            return failure("auth_expired", "business identity is no longer active");
        }
        ActionPreparation preparation = atomicPreparation.orElseThrow();
        if (preparation.errorCode() != null) {
            return failure(preparation.errorCode(), preparation.errorSummary());
        }
        PreparedAction prepared = preparation.action();
        try {
            emitAdded(emitter, prepared.requestedItem());
            prepared.progressListener().activate();
            try {
                protocol.sendActionRequest(prepared.requestedAction(), prepared.requestPayload())
                        .join();
            } catch (RuntimeException acknowledgementFailure) {
                Throwable cause = unwrap(acknowledgementFailure);
                if (cause instanceof ConfirmedActionRequestRejection rejection) {
                    return toResult(pending.confirmedRequestRejected(
                            prepared.executionId(), prepared.correlation(), prepared.connectionContext(),
                            rejection.errorCode(), "desktop rejected application action request").join());
                }
                return toResult(pending.acknowledgementUncertain(
                        prepared.executionId(), prepared.correlation(), prepared.connectionContext(),
                        "application action request acknowledgement uncertain").join());
            }
            return toResult(prepared.terminal().join());
        } catch (RuntimeException failure) {
            return failure("remote_request_failed", "desktop action request failed");
        }
    }

    private ActionPreparation prepareAction(
            BusinessIdentityScopeService.ActiveBusinessIdentity trusted,
            ApplicationToolInvocationContext.Invocation invocation,
            String actionId,
            Integer actionVersion,
            JsonNode input,
            String pageId,
            Long contextRevision,
            ItemEmitter emitter) {
        try {
            ApplicationCatalogRegistry.CatalogSnapshot catalog = catalogs.current(trusted.connection())
                    .orElseThrow(() -> new ActionValidation("action_not_found", "catalog unavailable"));
            ApplicationPageContextRegistry.PageContextSnapshot context = contexts.current(trusted.connection())
                    .orElseThrow(() -> new ActionValidation("context_stale", "page context unavailable"));
            if (catalog.catalogEpoch() != context.catalogEpoch()) {
                throw new ActionValidation("context_stale", "catalog and context do not match");
            }
            JsonNode descriptor = findAction(catalog.payload().path("actions"), actionId, actionVersion)
                    .orElseThrow(() -> new ActionValidation("action_not_found", "action not available"))
                    .deepCopy();
            validatePageContext(context.payload(), pageId, contextRevision);
            validateInput(input);
            PendingApplicationAction.Path path = path(descriptor.get("risk"));
            String executionId = executionIds.get();
            JsonNode normalizedInput = normalizeInput(descriptor, input, executionId);
            validateInput(normalizedInput);
            PendingApplicationAction.Correlation correlation = new PendingApplicationAction.Correlation(
                    invocation.threadId(), invocation.turnId(), invocation.toolCallId());
            PendingApplicationAction.ConnectionContext connectionContext = connectionContext(trusted);
            String itemId = "it_action_" + executionId.replaceAll("[^A-Za-z0-9]", "");
            long started = nanoTime.getAsLong();
            DeferredProgressListener<PendingApplicationAction> progress = new DeferredProgressListener<>(snapshot -> emitUpdated(
                    emitter, progressItem(itemId, actionId, descriptor, snapshot, elapsed(started))));
            String requestFingerprint = requestFingerprint(
                    actionId, actionVersion, normalizedInput, pageId, contextRevision);
            CompletableFuture<PendingApplicationAction> terminal = pending.register(
                    executionId, correlation, path, connectionContext,
                    new PendingApplicationActions.RegistrationMetadata(
                            actionId, actionVersion, requestFingerprint),
                    progress::accept);
            if (toolCalls != null) {
                try {
                    toolCalls.bindExecutionId(invocation.toolCallId(), invocation.businessIdentityScope(), executionId);
                } catch (RuntimeException bindingFailure) {
                    pending.cancel(executionId, correlation, "tool call execution binding failed");
                    throw bindingFailure;
                }
            }
            PendingApplicationAction requestedAction = new PendingApplicationAction(
                    executionId, correlation, path, PendingApplicationAction.State.REQUESTED,
                    null, null, Instant.now(), connectionContext);
            ApplicationActionItem requestedItem = item(
                    itemId, executionId, actionId, descriptor, "requested", null, null, null, 0L);
            ObjectNode frozenRequest = requestPayload(
                    actionId, actionVersion, normalizedInput, pageId, contextRevision);
            return ActionPreparation.success(new PreparedAction(
                    executionId, correlation, connectionContext, requestedAction, frozenRequest,
                    requestedItem, terminal, progress));
        } catch (ActionValidation validation) {
            return ActionPreparation.error(validation.errorCode, validation.getMessage());
        } catch (RuntimeException registrationFailure) {
            return ActionPreparation.error("local_persistence_failed", "local action registration failed");
        }
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

    static ApplicationActionToolResult toResult(PendingApplicationAction terminal) {
        String errorCode = terminal.payload() == null ? null : text(terminal.payload(), "errorCode");
        if (terminal.state() == PendingApplicationAction.State.OUTCOME_UNKNOWN) {
            errorCode = "outcome_unknown";
        }
        return new ApplicationActionToolResult(
                terminal.executionId(), terminal.state().name().toLowerCase(Locale.ROOT), errorCode,
                terminal.state() == PendingApplicationAction.State.COMPLETED
                        ? "desktop action completed" : "desktop action ended: " + terminal.state().name().toLowerCase(Locale.ROOT));
    }

    private Optional<JsonNode> findAction(JsonNode actions, String actionId, Integer version) {
        if (actionId == null || actionId.isBlank() || version == null || version <= 0) {
            return Optional.empty();
        }
        if (actions.isObject()) {
            JsonNode action = actions.get(actionId);
            return matches(action, actionId, version, false) ? Optional.of(action) : Optional.empty();
        }
        if (actions.isArray()) {
            for (JsonNode action : actions) {
                if (matches(action, actionId, version, true)) {
                    return Optional.of(action);
                }
            }
        }
        return Optional.empty();
    }

    private boolean matches(JsonNode action, String actionId, int version, boolean requireId) {
        if (action == null || !action.isObject()) {
            return false;
        }
        JsonNode descriptorVersion = action.get("version");
        if (descriptorVersion == null || !descriptorVersion.isIntegralNumber()
                || !descriptorVersion.canConvertToInt() || descriptorVersion.intValue() != version) {
            return false;
        }
        JsonNode id = action.get("id");
        return !requireId && id == null || id != null && id.isTextual() && actionId.equals(id.textValue());
    }

    private void validatePageContext(JsonNode context, String pageId, Long revision) {
        JsonNode contextPageId = context == null ? null : context.get("pageId");
        JsonNode contextRevision = context == null ? null : context.get("contextRevision");
        if (pageId == null || pageId.isBlank() || revision == null || revision <= 0
                || contextPageId == null || !contextPageId.isTextual()
                || !pageId.equals(contextPageId.textValue())
                || contextRevision == null || !contextRevision.isIntegralNumber()
                || !contextRevision.canConvertToLong()
                || revision.longValue() != contextRevision.longValue()) {
            throw new ActionValidation("context_stale", "page context revision is stale");
        }
    }

    private void validateInput(JsonNode input) {
        if (input == null || !input.isObject()) {
            throw new ActionValidation("validation_failed", "action input must be an object");
        }
        try {
            ApplicationProtocolValidator.validateActionInputSize(
                    input.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            throw new ActionValidation("validation_failed", "action input exceeds the safe size limit");
        }
    }

    private JsonNode normalizeInput(JsonNode descriptor, JsonNode input, String executionId) {
        if (!descriptor.has("inputSchema")) {
            return input.deepCopy();
        }
        JsonNode inputSchema = descriptor.get("inputSchema");
        if (inputSchema == null || !inputSchema.isObject()) {
            throw invalidExecutionIdSchema();
        }
        JsonNode properties = inputSchema.get("properties");
        JsonNode required = inputSchema.get("required");
        if ((properties != null && !properties.isObject())
                || (required != null && !required.isArray())) {
            throw invalidExecutionIdSchema();
        }
        boolean executionIdPropertyDeclared = properties != null && properties.has("executionId");
        boolean executionIdRequired = false;
        if (required != null) {
            for (JsonNode requiredProperty : required) {
                if (!requiredProperty.isTextual()) {
                    throw invalidExecutionIdSchema();
                }
                executionIdRequired |= "executionId".equals(requiredProperty.textValue());
            }
        }
        if (!executionIdPropertyDeclared && !executionIdRequired) {
            return input.deepCopy();
        }
        if (!executionIdPropertyDeclared || !executionIdRequired) {
            throw invalidExecutionIdSchema();
        }
        JsonNode executionIdProperty = properties.get("executionId");
        JsonNode executionIdType = executionIdProperty == null ? null : executionIdProperty.get("type");
        if (executionIdProperty == null || !executionIdProperty.isObject()
                || executionIdType == null || !executionIdType.isTextual()
                || !"string".equals(executionIdType.textValue())) {
            throw invalidExecutionIdSchema();
        }
        ObjectNode normalized = input.deepCopy();
        normalized.put("executionId", executionId);
        return normalized;
    }

    private ActionValidation invalidExecutionIdSchema() {
        return new ActionValidation("validation_failed", "reserved executionId schema is invalid");
    }

    private PendingApplicationAction.Path path(JsonNode riskNode) {
        if (riskNode == null || !riskNode.isTextual()) {
            throw new ActionValidation("validation_failed", "action risk metadata is invalid");
        }
        return switch (riskNode.textValue()) {
            case "read_only" -> PendingApplicationAction.Path.READ_ONLY;
            case "reversible_write" -> PendingApplicationAction.Path.REVERSIBLE_WRITE;
            case "high_risk" -> PendingApplicationAction.Path.HIGH_RISK;
            default -> throw new ActionValidation("validation_failed", "action risk metadata is invalid");
        };
    }

    private ObjectNode requestPayload(String actionId, int version, JsonNode input, String pageId, long revision) {
        ObjectNode payload = json.createObjectNode()
                .put("actionId", actionId)
                .put("actionVersion", version);
        payload.set("input", input.deepCopy());
        payload.put("state", "requested")
                .put("origin", "agent")
                .put("pageId", pageId)
                .put("contextRevision", revision);
        return payload;
    }

    /** 对已验证的动作边界做稳定摘要；原始输入永不写入动作审计表。 */
    private String requestFingerprint(
            String actionId,
            int actionVersion,
            JsonNode input,
            String pageId,
            long contextRevision) {
        ObjectNode material = json.createObjectNode()
                .put("actionId", actionId)
                .put("actionVersion", actionVersion)
                .put("contextRevision", contextRevision);
        material.set("input", canonicalize(input));
        material.put("pageId", pageId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = json.createObjectNode();
            ArrayList<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(field -> result.set(field, canonicalize(value.get(field))));
            return result;
        }
        if (value.isArray()) {
            var result = json.createArrayNode();
            value.forEach(element -> result.add(canonicalize(element)));
            return result;
        }
        return value.deepCopy();
    }

    private PendingApplicationAction.ConnectionContext connectionContext(
            BusinessIdentityScopeService.ActiveBusinessIdentity active) {
        var connection = active.connection();
        var identity = active.identity();
        return new PendingApplicationAction.ConnectionContext(
                connection.reservationId(), connection.webSocketSessionId(), connection.desktopInstanceId(),
                connection.desktopSessionId(), identity.authSessionId(), identity.identityEpoch(),
                identity.userId(), identity.tenantId(), identity.platformId());
    }

    private ApplicationActionItem progressItem(
            String itemId,
            String actionId,
            JsonNode descriptor,
            PendingApplicationAction action,
            long durationMs) {
        JsonNode payload = action.payload();
        return item(itemId, action.executionId(), actionId, descriptor, wireStatus(action.state()),
                text(payload, "previewSummary"), text(payload, "errorCode"), text(payload, "errorSummary"), durationMs);
    }

    private ApplicationActionItem item(
            String itemId, String executionId, String actionId, JsonNode descriptor, String status,
            String preview, String errorCode, String errorSummary, Long durationMs) {
        return new ApplicationActionItem(
                itemId, TYPE, executionId, actionId,
                safeText(descriptor.path("title").asText("Application action")),
                safeText(descriptor.path("risk").asText("read_only")), status,
                safeText(preview), safeText(errorCode), safeText(errorSummary), durationMs);
    }

    private String wireStatus(PendingApplicationAction.State state) {
        return state == PendingApplicationAction.State.APPROVAL_REQUIRED
                ? "approval_required" : state.name().toLowerCase(Locale.ROOT);
    }

    private long elapsed(long started) {
        return Math.max(0, Duration.ofNanos(nanoTime.getAsLong() - started).toMillis());
    }

    private ItemEmitter emitter(ToolContext context) {
        Object value = context == null ? null
                : context.getContext().get(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER);
        return value instanceof ItemEmitter emitter ? emitter : null;
    }

    private void emitAdded(ItemEmitter emitter, ApplicationActionItem item) {
        if (emitter == null) return;
        try {
            emitter.emitApplicationActionAdded(item);
        } catch (IOException ignored) {
            // 展示失败不得改变桌面动作的真实执行结果。
        }
    }

    private void emitUpdated(ItemEmitter emitter, ApplicationActionItem item) {
        if (emitter == null) return;
        try {
            emitter.emitApplicationActionUpdated(item);
        } catch (IOException ignored) {
            // 展示失败不得改变桌面动作的真实执行结果。
        }
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        return value == null || !value.isValueNode() ? null : value.asText();
    }

    private String safeText(String value) {
        return safeSummary.sanitize(value);
    }

    private ApplicationActionToolResult failure(String code, String summary) {
        return new ApplicationActionToolResult(null, "failed", code, summary);
    }

    private static final class ActionValidation extends RuntimeException {
        private final String errorCode;

        private ActionValidation(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }

    private record ActionPreparation(
            PreparedAction action,
            String errorCode,
            String errorSummary) {
        private static ActionPreparation success(PreparedAction action) {
            return new ActionPreparation(action, null, null);
        }

        private static ActionPreparation error(String errorCode, String errorSummary) {
            return new ActionPreparation(null, errorCode, errorSummary);
        }
    }

    private record PreparedAction(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext connectionContext,
            PendingApplicationAction requestedAction,
            ObjectNode requestPayload,
            ApplicationActionItem requestedItem,
            CompletableFuture<PendingApplicationAction> terminal,
            DeferredProgressListener<PendingApplicationAction> progressListener) {
    }
}
