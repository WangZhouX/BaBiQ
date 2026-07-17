package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.api.JsonRpcLogSupport;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ConversationEventRecorder;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
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
        this(conversationService, objectMapper, turnExecutor, null, null, null, null, null, null);
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
                eventRecorder, appSettingsService, null, null);
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
                eventRecorder, appSettingsService, workUnitService, null);
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
            BusinessIdentityScopeService businessIdentityScopeService) {
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.turnExecutor = turnExecutor;
        this.providerRegistry = providerRegistry;
        this.agentLoopProperties = agentLoopProperties;
        this.eventRecorder = eventRecorder;
        this.appSettingsService = appSettingsService;
        this.workUnitService = workUnitService;
        this.businessIdentityScopeService = businessIdentityScopeService;
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
        String threadId = requiredText(params, "threadId");
        String userText = requiredInputText(params);
        String providerId = optionalText(params, "providerId");
        log.info("turn/start 收到请求: threadId={}, providerId={}, inputChars={}, inputPreview={}",
                threadId,
                providerId == null ? "<active-provider>" : providerId,
                userText.length(),
                JsonRpcLogSupport.preview(userText));
        Thread thread = conversationService.findThread(threadId)
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
                        "threadId=" + threadId + " 不存在，无法创建 Turn"));
        BusinessIdentityScope requestScope = businessIdentityScopeService == null
                ? BusinessIdentityScope.UNSCOPED
                : resolveBusinessScope(session, threadId);
        if (!thread.businessIdentityScope().equals(requestScope)) {
            throw threadNotFound(threadId);
        }
        Turn turn = conversationService.startTurn(threadId);
        turn.start();
        ModelProviderConfig provider = resolveProvider(providerId);
        AppSettings settings = appSettingsService == null ? null : appSettingsService.get();
        String sandboxMode = settings == null ? defaultSandboxMode() : settings.sandboxMode();
        String approvalPolicy = settings == null ? defaultApprovalPolicy() : settings.approvalPolicy();
        AgentRunPolicy runPolicy = AgentRunPolicy.fromSnapshots(sandboxMode, approvalPolicy, agentLoopProperties);
        conversationService.persistTurnStarted(
                turn,
                userText,
                provider == null ? providerId : provider.id(),
                provider == null ? null : provider.model(),
                thread.cwd(),
                runPolicy.sandboxMode().name(),
                runPolicy.approvalPolicy().name());
        log.info("turn/start 已创建 Turn: threadId={}, turnId={}, cwd={}, providerId={}",
                threadId,
                turn.id(),
                thread.cwd(),
                providerId == null ? "<active-provider>" : providerId);

        ItemEmitter emitter = new ItemEmitter(session, objectMapper, threadId, turn.id(), eventRecorder);
        try {
            emitter.emitTurnStarted();
        } catch (Exception exception) {
            log.warn("发送 turn/started 失败 turnId={}", turn.id(), exception);
        }
        WorkUnitCreateRequest workUnitRequest = parseWorkUnitCreateRequest(params);
        if (workUnitRequest != null) {
            if (workUnitService == null) {
                throw new JsonRpcException(JsonRpcErrorCode.INTERNAL_ERROR, "工作容器服务未初始化");
            }
            WorkUnitItem item = workUnitService.createOrAppend(workUnitRequest, thread, turn, thread.cwd(), runPolicy);
            try {
                emitter.emitItemAdded(item);
                turn.complete();
                emitter.emitTurnCompleted("completed");
            } catch (Exception exception) {
                log.warn("发送 workUnit 创建事件失败 turnId={}, kind={}, name={}",
                        turn.id(), workUnitRequest.kind(), workUnitRequest.name(), exception);
                throw new JsonRpcException(JsonRpcErrorCode.INTERNAL_ERROR, "工作容器创建事件发送失败");
            }
            log.info("turn/start 已创建工作容器并跳过 AgentLoop: threadId={}, turnId={}, kind={}, name={}",
                    threadId,
                    turn.id(),
                    workUnitRequest.kind(),
                    workUnitRequest.name());
            return Map.of("turnId", turn.id());
        }
        String workUnitGoalId = parseWorkUnitStartGoalId(params, threadId);
        turnExecutor.submit(turn, userText, providerId, thread.cwd(), emitter, runPolicy, workUnitGoalId);
        log.info("turn/start 已提交 AgentLoop: threadId={}, turnId={}, providerId={}",
                threadId,
                turn.id(),
                providerId == null ? "<active-provider>" : providerId);
        return Map.of("turnId", turn.id());
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

    private String parseWorkUnitStartGoalId(JsonNode params, String threadId) {
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
        String workUnitId = requiredIntentText(intent, "workUnitId");
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

    private String requiredInputText(JsonNode params) {
        JsonNode textNode = params == null ? null : params.path("input").path("text");
        if (textNode == null || textNode.isMissingNode() || textNode.asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: input.text");
        }
        return textNode.asText();
    }
}
