package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.attachment.AttachmentHistoryResolver;
import com.wzx.babiq.server.attachment.AttachmentException;
import com.wzx.babiq.server.attachment.AttachmentPreparationService;
import com.wzx.babiq.server.attachment.AttachmentRequest;
import com.wzx.babiq.server.attachment.AttachmentReservationRegistry;
import com.wzx.babiq.server.attachment.PreparedTurnInput;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ConversationEventRecorder;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.conversation.items.WorkUnitItem;
import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.settings.AppSettings;
import com.wzx.babiq.server.settings.AppSettingsService;
import com.wzx.babiq.server.workunit.WorkUnitCreateRequest;
import com.wzx.babiq.server.workunit.WorkUnitGoal;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * turn/start 方法处理器。
 *
 * <p>P1-3a 起该 handler 不再发 mock item 流，而是创建 Turn、同步返回 turnId，
 * 再把真实 AgentLoop 提交给 TurnExecutor 异步执行。它存在的原因是协议线程不能被
 * 模型调用或 HITL 中断阻塞。</p>
 */
@Component
public class TurnStartHandler implements JsonRpcMethodHandler {

    private static final Logger log = LoggerFactory.getLogger(TurnStartHandler.class);
    private static final Pattern SAFE_ATTACHMENT_DISPLAY_ID =
            Pattern.compile("(?i)^A-[A-HJ-NP-Z2-9]{6}$");

    /** 创建或读取 thread/turn 的内存会话服务，是 turn/start 的状态来源。 */
    private final ConversationService conversationService;
    /** 把 JSON-RPC params 转成强类型 TurnStartParams，减少手写字段解析。 */
    private final ObjectMapper objectMapper;
    /** 后台 turn 调度器，handler 快速返回后由它继续执行 AgentLoop。 */
    private final TurnExecutor turnExecutor;
    /** 当前模型 Provider 注册表，用来把本轮实际 provider/model 写入 turn 快照。 */
    private final ModelProviderRegistry providerRegistry;
    /** Agent 配置快照，用来保存本轮沙箱和审批策略。 */
    private final AgentLoopProperties agentLoopProperties;
    /** 运行事件记录器，会被传给 ItemEmitter，实现先落库再推送。 */
    private final ConversationEventRecorder eventRecorder;
    /** 应用设置服务，用来读取下一轮 turn 生效的沙箱和审批策略。 */
    private final AppSettingsService appSettingsService;
    private final WorkUnitService workUnitService;
    /** 用当前连接 scope 验证 Thread 归属，严格在创建 Turn 之前执行。 */
    private final BusinessIdentityScopeService businessIdentityScopeService;
    /** 在任何 Turn 状态变化前权威校验本轮新选附件。 */
    private final AttachmentPreparationService attachmentPreparationService;
    /** 只从当前 thread/scope 的完整历史解析明确的附件短标识。 */
    private final AttachmentHistoryResolver attachmentHistoryResolver;
    private final AttachmentReservationRegistry attachmentReservationRegistry;

    /**
     * 创建 turn/start handler。
     *
     * @param conversationService 对话生命周期服务
     * @param objectMapper JSON 序列化器
     * @param turnExecutor Agent 异步执行器
     */
    public TurnStartHandler(
            ConversationService conversationService,
            ObjectMapper objectMapper,
            TurnExecutor turnExecutor) {
        this(conversationService, objectMapper, turnExecutor, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 创建生产环境 turn/start handler。
     *
     * @param conversationService 对话生命周期服务
     * @param objectMapper JSON 序列化器
     * @param turnExecutor Agent 异步执行器
     * @param providerRegistry 模型 Provider 注册表
     * @param agentLoopProperties Agent 配置
     * @param eventRecorder 运行事件记录器
     * @param appSettingsService 应用设置服务
     */
    public TurnStartHandler(
            ConversationService conversationService,
            ObjectMapper objectMapper,
            TurnExecutor turnExecutor,
            ModelProviderRegistry providerRegistry,
            AgentLoopProperties agentLoopProperties,
            ConversationEventRecorder eventRecorder,
            AppSettingsService appSettingsService) {
        this(conversationService, objectMapper, turnExecutor, providerRegistry, agentLoopProperties,
                eventRecorder, appSettingsService, null, null, null, null, null);
    }

    public TurnStartHandler(
            ConversationService conversationService,
            ObjectMapper objectMapper,
            TurnExecutor turnExecutor,
            ModelProviderRegistry providerRegistry,
            AgentLoopProperties agentLoopProperties,
            ConversationEventRecorder eventRecorder,
            AppSettingsService appSettingsService,
            WorkUnitService workUnitService) {
        this(conversationService, objectMapper, turnExecutor, providerRegistry, agentLoopProperties,
                eventRecorder, appSettingsService, workUnitService, null, null, null, null);
    }

    /** 兼容显式注入业务身份服务的旧测试和宿主。 */
    public TurnStartHandler(
            ConversationService conversationService,
            ObjectMapper objectMapper,
            TurnExecutor turnExecutor,
            ModelProviderRegistry providerRegistry,
            AgentLoopProperties agentLoopProperties,
            ConversationEventRecorder eventRecorder,
            AppSettingsService appSettingsService,
            WorkUnitService workUnitService,
            BusinessIdentityScopeService businessIdentityScopeService) {
        this(conversationService, objectMapper, turnExecutor, providerRegistry, agentLoopProperties,
                eventRecorder, appSettingsService, workUnitService, businessIdentityScopeService, null, null, null);
    }

    public TurnStartHandler(
            ConversationService conversationService,
            ObjectMapper objectMapper,
            TurnExecutor turnExecutor,
            ModelProviderRegistry providerRegistry,
            AgentLoopProperties agentLoopProperties,
            ConversationEventRecorder eventRecorder,
            AppSettingsService appSettingsService,
            WorkUnitService workUnitService,
            BusinessIdentityScopeService businessIdentityScopeService,
            AttachmentPreparationService attachmentPreparationService,
            AttachmentHistoryResolver attachmentHistoryResolver) {
        this(conversationService, objectMapper, turnExecutor, providerRegistry, agentLoopProperties,
                eventRecorder, appSettingsService, workUnitService, businessIdentityScopeService,
                attachmentPreparationService, attachmentHistoryResolver, null);
    }

    @Autowired
    public TurnStartHandler(
            ConversationService conversationService,
            ObjectMapper objectMapper,
            TurnExecutor turnExecutor,
            ModelProviderRegistry providerRegistry,
            AgentLoopProperties agentLoopProperties,
            ConversationEventRecorder eventRecorder,
            AppSettingsService appSettingsService,
            WorkUnitService workUnitService,
            BusinessIdentityScopeService businessIdentityScopeService,
            AttachmentPreparationService attachmentPreparationService,
            AttachmentHistoryResolver attachmentHistoryResolver,
            AttachmentReservationRegistry attachmentReservationRegistry) {
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.turnExecutor = turnExecutor;
        this.providerRegistry = providerRegistry;
        this.agentLoopProperties = agentLoopProperties;
        this.eventRecorder = eventRecorder;
        this.appSettingsService = appSettingsService;
        this.workUnitService = workUnitService;
        this.businessIdentityScopeService = businessIdentityScopeService;
        this.attachmentPreparationService = attachmentPreparationService;
        this.attachmentHistoryResolver = attachmentHistoryResolver;
        this.attachmentReservationRegistry = attachmentReservationRegistry;
    }

    /**
     * 返回当前 handler 绑定的 JSON-RPC method。
     *
     * @return turn/start
     */
    @Override
    public String method() {
        return "turn/start";
    }

    /**
     * 创建 turn 并提交真实 AgentLoop。
     *
     * @param params 必须包含 threadId 与 input.text，可选 providerId
     * @param session 当前 WebSocket 会话
     * @return 包含 turnId 的响应对象
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        try {
            return handleRequest(params, session);
        } catch (AttachmentException failure) {
            String threadId = safeThreadId(params);
            log.warn(
                    "turn/start attachment rejected: threadId={}, attachmentCount={}, "
                            + "attachmentDisplayIds={}, attachmentCode={}, reasonType={}",
                    threadId,
                    attachmentCount(params),
                    safeAttachmentDisplayIds(params),
                    failure.code().name(),
                    failure.getClass().getSimpleName());
            throw new JsonRpcException(
                    JsonRpcErrorCode.INVALID_PARAMS,
                    "附件处理失败，请检查附件后重试",
                    Map.of("attachmentCode", failure.code().name()));
        }
    }

    private Object handleRequest(JsonNode params, WebSocketSession session) {
        String threadId = requiredText(params, "threadId");
        TurnInputRequest input = parseTurnInput(params);
        String userText = input.text();
        String providerId = optionalText(params, "providerId");
        WorkUnitCreateRequest workUnitRequest = parseWorkUnitCreateRequest(params);
        if (workUnitRequest != null && workUnitService == null) {
            throw new JsonRpcException(JsonRpcErrorCode.INTERNAL_ERROR, "工作容器服务未初始化");
        }
        String workUnitIdToStart = workUnitRequest == null ? parseWorkUnitStartId(params) : null;
        log.info("turn/start 收到请求: threadId={}, attachmentCount={}",
                threadId,
                input.attachments().size());
        BusinessIdentityScope requestScope = businessIdentityScopeService == null
                ? BusinessIdentityScope.UNSCOPED
                : resolveBusinessScope(session, threadId);
        ModelProviderConfig provider = resolveProvider(providerId);
        AppSettings settings = appSettingsService == null ? null : appSettingsService.get();
        String sandboxMode = settings == null ? defaultSandboxMode() : settings.sandboxMode();
        String approvalPolicy = settings == null ? defaultApprovalPolicy() : settings.approvalPolicy();
        AgentRunPolicy runPolicy = AgentRunPolicy.fromSnapshots(sandboxMode, approvalPolicy, agentLoopProperties);
        StartedTurn started = requestScope.scoped()
                ? createScopedTurn(
                        threadId, requestScope, input, providerId, provider, runPolicy,
                        workUnitRequest != null, workUnitIdToStart)
                : createAndStartTurn(
                        threadId, requestScope, input, providerId, provider, runPolicy,
                        workUnitRequest != null, workUnitIdToStart);
        Thread thread = started.thread();
        Turn turn = started.turn();
        log.info(
                "turn/start 附件准备完成: threadId={}, attachmentCount={}, "
                        + "attachmentTotalBytes={}, attachmentDisplayIds={}",
                threadId,
                started.input().allAttachments().size(),
                started.input().allAttachments().stream()
                        .mapToLong(item -> item.metadata().sizeBytes())
                        .sum(),
                started.input().allAttachments().stream()
                        .map(item -> item.metadata().displayId())
                        .toList());

        ItemEmitter emitter = new ItemEmitter(session, objectMapper, threadId, turn.id(), eventRecorder);
        try {
            emitter.emitTurnStarted();
        } catch (Exception exception) {
            failStartedTurn(turn, "turn/started", exception);
            started.releaseReservation();
            throw new JsonRpcException(JsonRpcErrorCode.INTERNAL_ERROR, "Turn 启动事件发送失败");
        }
        if (workUnitRequest != null) {
            try {
                WorkUnitItem item = workUnitService.createOrAppend(
                        workUnitRequest, thread, turn, thread.cwd(), runPolicy);
                emitter.emitItemAdded(item);
                if (!conversationService.completeTurnIfRunning(turn)) {
                    throw new IllegalStateException("work unit turn completion CAS failed");
                }
            } catch (Exception exception) {
                failStartedTurn(turn, "work_unit", exception);
                started.releaseReservation();
                throw new JsonRpcException(JsonRpcErrorCode.INTERNAL_ERROR, "工作容器创建事件发送失败");
            }
            try {
                emitter.emitPersistedTurnCompleted("completed");
            } catch (Exception notificationFailure) {
                log.warn("Work unit completion notification failed: reasonType={}",
                        notificationFailure.getClass().getSimpleName());
            }
            log.info("turn/start 已创建工作容器并跳过 AgentLoop: threadId={}, turnId={}, kind={}, name={}",
                    threadId,
                    turn.id(),
                    workUnitRequest.kind(),
                    workUnitRequest.name());
            started.releaseReservation();
            return Map.of("turnId", turn.id());
        }
        try {
            turnExecutor.submit(
                    turn, started.input(), providerId, thread.cwd(), emitter, runPolicy,
                    started.workUnitGoalId());
        } catch (RuntimeException exception) {
            failStartedTurn(turn, "executor_submit", exception);
            started.releaseReservation();
            throw new JsonRpcException(JsonRpcErrorCode.INTERNAL_ERROR, "Turn 提交失败");
        }
        log.info("turn/start 已提交 AgentLoop: threadId={}, turnId={}", threadId, turn.id());
        return Map.of("turnId", turn.id());
    }

    private static String safeThreadId(JsonNode params) {
        JsonNode value = params == null ? null : params.get("threadId");
        if (value == null || !value.isTextual()) {
            return "<unknown>";
        }
        String threadId = value.textValue();
        return threadId != null && threadId.matches("[A-Za-z0-9_-]{1,96}")
                ? threadId
                : "<invalid>";
    }

    private static int attachmentCount(JsonNode params) {
        JsonNode attachments = params == null ? null : params.path("input").path("attachments");
        return attachments != null && attachments.isArray() ? attachments.size() : 0;
    }

    private static List<String> safeAttachmentDisplayIds(JsonNode params) {
        JsonNode attachments = params == null ? null : params.path("input").path("attachments");
        if (attachments == null || !attachments.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode attachment : attachments) {
            JsonNode displayId = attachment == null ? null : attachment.get("displayId");
            if (displayId != null
                    && displayId.isTextual()
                    && SAFE_ATTACHMENT_DISPLAY_ID.matcher(displayId.textValue()).matches()) {
                result.add(displayId.textValue().toUpperCase(java.util.Locale.ROOT));
            }
        }
        return List.copyOf(result);
    }

    private void failStartedTurn(Turn turn, String step, Throwable failure) {
        conversationService.failTurnIfRunning(turn, "turn_start_submission_failed");
        log.warn("Turn start failed before asynchronous execution: step={}, reasonType={}",
                step, failure.getClass().getSimpleName());
    }

    /** 在业务 connection 临界区内完成精确归属校验、CREATED 持久化和 RUNNING CAS。 */
    private StartedTurn createAndStartTurn(
            String threadId,
            BusinessIdentityScope requestScope,
            TurnInputRequest inputRequest,
            String requestedProviderId,
            ModelProviderConfig provider,
            AgentRunPolicy runPolicy,
            boolean createWorkUnit,
            String workUnitIdToStart) {
        Thread thread = conversationService.findThread(threadId, requestScope)
                .orElseThrow(() -> threadNotFound(threadId));
        PreparedTurnInput prepared = prepareNewInput(inputRequest);
        AttachmentReservationRegistry.Reservation reservation = null;
        if (!createWorkUnit && attachmentReservationRegistry != null) {
            reservation = attachmentReservationRegistry.reserve(
                    threadId, requestScope, prepared.newAttachments());
        }
        try {
            PreparedTurnInput input = resolveHistory(threadId, requestScope, prepared);
            return startPreparedTurn(
                    thread,
                    requestScope,
                    input,
                    requestedProviderId,
                    provider,
                    runPolicy,
                    createWorkUnit,
                    workUnitIdToStart,
                    reservation);
        } catch (RuntimeException failure) {
            if (reservation != null) {
                reservation.close();
            }
            throw failure;
        }
    }

    private StartedTurn startPreparedTurn(
            Thread thread,
            BusinessIdentityScope requestScope,
            PreparedTurnInput input,
            String requestedProviderId,
            ModelProviderConfig provider,
            AgentRunPolicy runPolicy,
            boolean createWorkUnit,
            String workUnitIdToStart,
            AttachmentReservationRegistry.Reservation reservation
    ) {
        if (createWorkUnit && !input.allAttachments().isEmpty()) {
            throw new JsonRpcException(
                    JsonRpcErrorCode.INVALID_PARAMS,
                    "创建工作容器暂不支持附件");
        }
        String workUnitGoalId = selectWorkUnitGoal(thread.id(), workUnitIdToStart);
        Turn turn = conversationService.startTurn(thread.id(), requestScope);
        if (reservation != null) {
            reservation.bindToTurn(turn.id());
        }
        if (!requestScope.scoped()) {
            turn.start();
            conversationService.persistTurnStarted(
                    turn, input.text(),
                    provider == null ? requestedProviderId : provider.id(),
                    provider == null ? null : provider.model(),
                    thread.cwd(), runPolicy.sandboxMode().name(), runPolicy.approvalPolicy().name());
            return new StartedTurn(thread, turn, input, workUnitGoalId, reservation);
        }
        conversationService.persistTurnStarted(
                turn, input.text(),
                provider == null ? requestedProviderId : provider.id(),
                provider == null ? null : provider.model(),
                thread.cwd(), runPolicy.sandboxMode().name(), runPolicy.approvalPolicy().name());
        if (!conversationService.transitionPreExecutionToRunning(turn, TurnStatus.CREATED)) {
            throw threadNotFound(thread.id());
        }
        return new StartedTurn(thread, turn, input, workUnitGoalId, reservation);
    }

    private StartedTurn createScopedTurn(
            String threadId,
            BusinessIdentityScope requestScope,
            TurnInputRequest input,
            String requestedProviderId,
            ModelProviderConfig provider,
            AgentRunPolicy runPolicy,
            boolean createWorkUnit,
            String workUnitIdToStart
    ) {
        ScopedStartResult result = businessIdentityScopeService.withActiveConnectionScope(
                        requestScope,
                        active -> captureScopedStart(() -> createAndStartTurn(
                                threadId,
                                requestScope,
                                input,
                                requestedProviderId,
                                provider,
                                runPolicy,
                                createWorkUnit,
                                workUnitIdToStart)))
                .orElseThrow(() -> threadNotFound(threadId));
        if (result.failure() != null) {
            throw result.failure();
        }
        return result.started();
    }

    private static ScopedStartResult captureScopedStart(StartOperation operation) {
        try {
            return new ScopedStartResult(operation.start(), null);
        } catch (RuntimeException failure) {
            return new ScopedStartResult(null, failure);
        }
    }

    private PreparedTurnInput prepareNewInput(TurnInputRequest input) {
        PreparedTurnInput prepared;
        if (attachmentPreparationService == null) {
            if (!input.attachments().isEmpty()) {
                throw new JsonRpcException(
                        JsonRpcErrorCode.INTERNAL_ERROR,
                        "附件服务未初始化");
            }
            prepared = new PreparedTurnInput(input.text(), List.of(), List.of());
        } else {
            prepared = attachmentPreparationService.prepareNew(input.text(), input.attachments());
        }
        return prepared;
    }

    private PreparedTurnInput resolveHistory(
            String threadId,
            BusinessIdentityScope requestScope,
            PreparedTurnInput prepared
    ) {
        return attachmentHistoryResolver == null
                ? prepared
                : attachmentHistoryResolver.resolve(threadId, requestScope, prepared);
    }

    private record StartedTurn(
            Thread thread,
            Turn turn,
            PreparedTurnInput input,
            String workUnitGoalId,
            AttachmentReservationRegistry.Reservation reservation
    ) {

        private void releaseReservation() {
            if (reservation != null) {
                reservation.close();
            }
        }
    }

    private record ScopedStartResult(StartedTurn started, RuntimeException failure) {
    }

    @FunctionalInterface
    private interface StartOperation {
        StartedTurn start();
    }

    private record TurnInputRequest(String text, List<AttachmentRequest> attachments) {
        private TurnInputRequest {
            text = text == null ? "" : text;
            attachments = List.copyOf(attachments);
        }
    }

    private BusinessIdentityScope resolveBusinessScope(WebSocketSession session, String threadId) {
        try {
            return businessIdentityScopeService.resolve(session);
        } catch (IllegalStateException exception) {
            throw threadNotFound(threadId);
        }
    }

    private JsonRpcException threadNotFound(String threadId) {
        return new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
                "threadId=" + threadId + " 不存在，无法创建 Turn");
    }

    private ModelProviderConfig resolveProvider(String providerId) {
        if (providerRegistry == null) {
            return null;
        }
        return providerId == null ? providerRegistry.active() : providerRegistry.get(providerId);
    }

    private String defaultSandboxMode() {
        return agentLoopProperties == null ? null : agentLoopProperties.sandboxMode().name();
    }

    private String defaultApprovalPolicy() {
        return agentLoopProperties == null ? null : agentLoopProperties.approvalPolicy().name();
    }

    private String requiredText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return params.get(fieldName).asText();
    }

    private String optionalText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            return null;
        }
        return params.get(fieldName).asText();
    }

    private WorkUnitCreateRequest parseWorkUnitCreateRequest(JsonNode params) {
        JsonNode intent = params == null ? null : params.path("executionIntent");
        if (intent == null || intent.isMissingNode() || intent.isNull()) {
            return null;
        }
        String type = requiredIntentText(intent, "type");
        if (!"create_work_unit".equals(type)) {
            return null;
        }
        String kind = requiredIntentText(intent, "kind");
        String name = requiredIntentText(intent, "name");
        String goal = requiredIntentText(intent, "goal");
        String goalId = optionalText(intent, "goalId");
        if (!"orchestration".equals(kind) && !"team".equals(kind)) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
                    "executionIntent.kind 仅支持 orchestration 或 team");
        }
        return new WorkUnitCreateRequest(kind, name, goal, goalId);
    }

    private String parseWorkUnitStartId(JsonNode params) {
        JsonNode intent = params == null ? null : params.path("executionIntent");
        if (intent == null || intent.isMissingNode() || intent.isNull()) {
            return null;
        }
        String type = requiredIntentText(intent, "type");
        if (!"start_work_unit".equals(type)) {
            return null;
        }
        if (workUnitService == null) {
            throw new JsonRpcException(JsonRpcErrorCode.INTERNAL_ERROR, "工作容器服务未初始化");
        }
        return requiredIntentText(intent, "workUnitId");
    }

    private String selectWorkUnitGoal(String threadId, String workUnitId) {
        if (workUnitId == null) {
            return null;
        }
        try {
            WorkUnitGoal goal = workUnitService.selectPendingGoalForTurn(threadId, workUnitId);
            return goal.goalId();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, exception.getMessage());
        }
    }

    private String requiredIntentText(JsonNode intent, String fieldName) {
        if (intent == null || !intent.hasNonNull(fieldName) || intent.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
                    "缺少必填字段: executionIntent." + fieldName);
        }
        return intent.get(fieldName).asText().trim();
    }

    private TurnInputRequest parseTurnInput(JsonNode params) {
        JsonNode input = params == null ? null : params.get("input");
        if (input == null || !input.isObject()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: input");
        }
        JsonNode textNode = input.get("text");
        if (textNode != null && !textNode.isNull() && !textNode.isTextual()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "input.text 必须是文本");
        }
        String text = textNode == null || textNode.isNull() ? "" : textNode.textValue();
        JsonNode attachmentsNode = input.get("attachments");
        List<AttachmentRequest> attachments = new ArrayList<>();
        if (attachmentsNode != null && !attachmentsNode.isNull()) {
            if (!attachmentsNode.isArray()) {
                throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "input.attachments 必须是数组");
            }
            for (JsonNode attachmentNode : attachmentsNode) {
                if (attachmentNode == null || !attachmentNode.isObject()) {
                    throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "附件描述格式无效");
                }
                try {
                    attachments.add(objectMapper.treeToValue(attachmentNode, AttachmentRequest.class));
                } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                    throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "附件描述格式无效");
                }
            }
        }
        if (text.isBlank() && attachments.isEmpty()) {
            throw new JsonRpcException(
                    JsonRpcErrorCode.INVALID_PARAMS,
                    "input.text 和 input.attachments 不能同时为空");
        }
        return new TurnInputRequest(text, attachments);
    }
}
