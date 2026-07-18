package com.wzx.babiq.server.api.method;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.agent.PendingApprovals;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.approval.ApprovalRuleService;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ConversationEventRecorder;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.service.ApprovalPersistenceService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * approval/respond 方法处理器。
 *
 * <p>P1-3a 使用 SAA 原生 HITL interrupt/resume。该 handler 不手写审批状态机，
 * 只把用户决策转成 InterruptionMetadata.ToolFeedback，再通过 RunnableConfig.resume
 * 所需的数据提交给 TurnExecutor 续跑。</p>
 */
@Component
public class ApprovalRespondHandler implements JsonRpcMethodHandler {

    /** threadId -> HITL 暂停点，用户审批后从这里取回 InterruptionMetadata。 */
    private final PendingApprovals pendingApprovals;
    /** 查询 turn/thread 状态，确保审批响应能找到原来的执行轮次和工作目录。 */
    private final ConversationService conversationService;
    /** 把 JSON-RPC params 转成 ApprovalRespondParams。 */
    private final ObjectMapper objectMapper;
    /** 审批通过或编辑后交给它恢复 AgentLoop；拒绝则直接关闭 turn。 */
    private final TurnExecutor turnExecutor;
    /** 记录 approve/deny/edit 等审批决策指标。 */
    private final BaBiQMetrics metrics;
    /** 运行事件记录器，审批恢复后的新 item 也必须进入历史数据库。 */
    private final ConversationEventRecorder eventRecorder;
    /** Always 规则服务；为空时表示旧单元测试环境不启用 always 持久化。 */
    private final ApprovalRuleService approvalRuleService;
    /** 审批表服务；为空时表示旧单元测试环境不落审批历史。 */
    private final ApprovalPersistenceService approvalPersistenceService;
    /** turn 持久化服务；用于审批恢复时取回原 turn 的权限快照。 */
    private final TurnPersistenceService turnPersistenceService;
    /** yml 默认配置；当历史 turn 缺少快照时作为兼容回退。 */
    private final AgentLoopProperties agentLoopProperties;
    /** 业务模式下把审批恢复与身份切换线性化到同一个 connection monitor。 */
    private final BusinessIdentityScopeService businessIdentityScopeService;

    /**
     * 创建 approval/respond handler。
     *
     * @param pendingApprovals 待审批元数据缓存
     * @param conversationService 对话生命周期服务
     * @param objectMapper JSON 序列化器
     * @param turnExecutor Agent 异步执行器
     * @param metrics P1 可观测指标聚合器
     */
    public ApprovalRespondHandler(PendingApprovals pendingApprovals,
                                  ConversationService conversationService,
                                  ObjectMapper objectMapper,
                                 TurnExecutor turnExecutor,
                                 BaBiQMetrics metrics) {
        this(pendingApprovals, conversationService, objectMapper, turnExecutor,
                metrics, null, null, null, null, null, null);
    }

    /**
     * 创建带持久化记录器的 approval/respond handler。
     *
     * @param pendingApprovals 待审批元数据缓存
     * @param conversationService 对话生命周期服务
     * @param objectMapper JSON 序列化器
     * @param turnExecutor Agent 异步执行器
     * @param metrics P1 可观测指标聚合器
     * @param eventRecorder 运行事件记录器
     * @param approvalRuleService Always 规则服务
     */
    public ApprovalRespondHandler(PendingApprovals pendingApprovals,
                                  ConversationService conversationService,
                                  ObjectMapper objectMapper,
                                  TurnExecutor turnExecutor,
                                  BaBiQMetrics metrics,
                                  ConversationEventRecorder eventRecorder,
                                  ApprovalRuleService approvalRuleService) {
        this(pendingApprovals, conversationService, objectMapper, turnExecutor,
                metrics, eventRecorder, approvalRuleService, null, null, null, null);
    }

    /**
     * 创建带审批持久化服务的 approval/respond handler。
     *
     * @param pendingApprovals 待审批元数据缓存
     * @param conversationService 对话生命周期服务
     * @param objectMapper JSON 序列化器
     * @param turnExecutor Agent 异步执行器
     * @param metrics P1 可观测指标聚合器
     * @param eventRecorder 运行事件记录器
     * @param approvalRuleService Always 规则服务
     * @param approvalPersistenceService 审批持久化服务
     */
    public ApprovalRespondHandler(PendingApprovals pendingApprovals,
                                  ConversationService conversationService,
                                  ObjectMapper objectMapper,
                                  TurnExecutor turnExecutor,
                                  BaBiQMetrics metrics,
                                  ConversationEventRecorder eventRecorder,
                                  ApprovalRuleService approvalRuleService,
                                  ApprovalPersistenceService approvalPersistenceService) {
        this(pendingApprovals, conversationService, objectMapper, turnExecutor,
                metrics, eventRecorder, approvalRuleService, approvalPersistenceService, null, null, null);
    }

    /**
     * 创建完整生产链路的 approval/respond handler。
     *
     * @param pendingApprovals 待审批元数据缓存
     * @param conversationService 对话生命周期服务
     * @param objectMapper JSON 序列化器
     * @param turnExecutor Agent 异步执行器
     * @param metrics P1 可观测指标聚合器
     * @param eventRecorder 运行事件记录器
     * @param approvalRuleService Always 规则服务
     * @param approvalPersistenceService 审批持久化服务
     * @param turnPersistenceService turn 持久化服务，用于恢复权限快照
     * @param agentLoopProperties yml 默认 Agent 配置
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalRespondHandler(PendingApprovals pendingApprovals,
                                  ConversationService conversationService,
                                  ObjectMapper objectMapper,
                                  TurnExecutor turnExecutor,
                                  BaBiQMetrics metrics,
                                  ConversationEventRecorder eventRecorder,
                                  ApprovalRuleService approvalRuleService,
                                  ApprovalPersistenceService approvalPersistenceService,
                                  TurnPersistenceService turnPersistenceService,
                                  AgentLoopProperties agentLoopProperties,
                                  BusinessIdentityScopeService businessIdentityScopeService) {
        this.pendingApprovals = pendingApprovals;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.turnExecutor = turnExecutor;
        this.metrics = metrics;
        this.eventRecorder = eventRecorder;
        this.approvalRuleService = approvalRuleService;
        this.approvalPersistenceService = approvalPersistenceService;
        this.turnPersistenceService = turnPersistenceService;
        this.agentLoopProperties = agentLoopProperties;
        this.businessIdentityScopeService = businessIdentityScopeService;
    }

    /**
     * 返回当前 handler 绑定的 JSON-RPC method。
     *
     * @return approval/respond
     */
    @Override
    public String method() {
        return "approval/respond";
    }

    /**
     * 接收用户审批并提交 HITL resume。
     *
     * @param params 必须包含 threadId、turnId、decision；edit 时可带 editedArgs
     * @param session 当前 WebSocket 会话
     * @return delivered=true
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = requiredText(params, "threadId");
        String turnId = requiredText(params, "turnId");
        String decision = requiredText(params, "decision");
        String editedArgs = optionalText(params, "editedArgs");
        String scope = optionalText(params, "scope");
        BusinessIdentityScope requestScope = resolveScope(session);
        InterruptionMetadata original = pendingApprovals.peek(threadId);
        if (original == null) {
            throw unavailableApproval(threadId);
        }
        String canonicalDecision = canonicalDecision(decision);
        InterruptionMetadata feedback = buildFeedback(original, decision, editedArgs);
        validateAlwaysDecision(decision, scope);
        AgentRunPolicy runPolicy = prepareRunPolicy(turnId, requestScope);
        PreparedResume prepared = requestScope.scoped()
                ? businessIdentityScopeService.withActiveConnectionScope(requestScope,
                        active -> prepareResume(threadId, turnId, requestScope, original))
                        .orElseThrow(() -> unavailableApproval(threadId))
                : prepareResume(threadId, turnId, requestScope, original);
        Turn turn = prepared.turn();
        Thread thread = prepared.thread();
        ItemEmitter emitter = new ItemEmitter(session, objectMapper, threadId, turnId, eventRecorder);
        try {
            resolveApprovalIfPossible(threadId, turnId, canonicalDecision, scope, editedArgs);
            rememberAlwaysRulesIfNeeded(threadId, decision, scope, original);
            metrics.recordApprovalDecision(canonicalDecision);
            turnExecutor.submitResume(turn, feedback, thread.cwd(), emitter, runPolicy);
        } catch (RuntimeException exception) {
            conversationService.failTurnIfRunning(turn, "resume_submission_failed");
            pendingApprovals.removeExact(threadId, original);
            throw new JsonRpcException(JsonRpcErrorCode.INTERNAL_ERROR, "审批恢复提交失败");
        }
        return Map.of("delivered", true);
    }

    /** 在 connection monitor 内校验完整 scope、CAS 恢复 Turn 并单次消费审批元数据。 */
    private PreparedResume prepareResume(
            String threadId,
            String turnId,
            BusinessIdentityScope requestScope,
            InterruptionMetadata expectedApproval) {
        Turn turn = requestScope.scoped()
                ? conversationService.findTurn(turnId, requestScope).orElse(null)
                : conversationService.findTurn(turnId).orElse(null);
        Thread thread = requestScope.scoped()
                ? conversationService.findThread(threadId, requestScope).orElse(null)
                : conversationService.findThread(threadId).orElse(null);
        if (turn == null || thread == null || expectedApproval == null
                || !turn.threadId().equals(threadId)
                || !conversationService.transitionPreExecutionToRunning(turn, TurnStatus.WAITING_APPROVAL)) {
            throw unavailableApproval(threadId);
        }
        InterruptionMetadata claimed = pendingApprovals.claim(threadId, expectedApproval);
        if (claimed == null) {
            conversationService.failTurnIfRunning(turn, "resume_submission_failed");
            throw unavailableApproval(threadId);
        }
        return new PreparedResume(thread, turn, claimed);
    }

    private BusinessIdentityScope resolveScope(WebSocketSession session) {
        if (businessIdentityScopeService == null) {
            return BusinessIdentityScope.UNSCOPED;
        }
        try {
            return businessIdentityScopeService.resolve(session);
        } catch (IllegalStateException failure) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "当前业务身份已失效");
        }
    }

    private JsonRpcException unavailableApproval(String threadId) {
        return new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "没有可恢复的待审批请求: " + threadId);
    }

    private record PreparedResume(Thread thread, Turn turn, InterruptionMetadata original) {
    }

    /**
     * 根据用户决策构造 SAA HITL 反馈元数据。
     *
     * @param original 原始中断元数据
     * @param decision 用户决策 approve/deny/edit
     * @param editedArgs edit 模式下的新参数
     * @return 可用于 RunnableConfig.addHumanFeedback 的元数据
     */
    public InterruptionMetadata buildFeedback(InterruptionMetadata original, String decision, String editedArgs) {
        InterruptionMetadata.Builder builder = InterruptionMetadata.builder(original);
        builder.toolFeedbacks(java.util.List.of());
        for (InterruptionMetadata.ToolFeedback feedback : original.toolFeedbacks()) {
            builder.addToolFeedback(convertFeedback(feedback, decision, editedArgs));
        }
        return builder.build();
    }

    private InterruptionMetadata.ToolFeedback convertFeedback(
            InterruptionMetadata.ToolFeedback feedback, String decision, String editedArgs) {
        InterruptionMetadata.ToolFeedback.Builder builder = InterruptionMetadata.ToolFeedback.builder(feedback);
        return switch (decision.toLowerCase()) {
            case "approve", "approved", "always" -> builder
                    .result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED)
                    .build();
            case "deny", "denied", "reject", "rejected" -> builder
                    .result(InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED)
                    .description("用户拒绝执行")
                    .build();
            case "edit", "edited" -> builder
                    .result(InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED)
                    .arguments(editedArgs == null ? feedback.getArguments() : editedArgs)
                    .build();
            default -> throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "未知审批决策: " + decision);
        };
    }

    private String canonicalDecision(String decision) {
        return switch (decision.toLowerCase()) {
            case "approve", "approved" -> "approved";
            case "deny", "denied", "reject", "rejected" -> "denied";
            case "edit", "edited" -> "edited";
            case "always" -> "always";
            default -> throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "未知审批决策: " + decision);
        };
    }

    private void rememberAlwaysRulesIfNeeded(String threadId, String decision, String requestedScope,
                                             InterruptionMetadata original) {
        if (!"always".equalsIgnoreCase(decision)) {
            return;
        }
        if (approvalRuleService == null) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "当前后端未启用 always 审批规则");
        }
        String scope = requestedScope == null ? ApprovalRuleService.SCOPE_SESSION : requestedScope;
        for (InterruptionMetadata.ToolFeedback feedback : original.toolFeedbacks()) {
            // Always 的安全边界由 ApprovalRuleService 保证：只绑定当前 thread、tool 和参数指纹。
            approvalRuleService.rememberAlways(threadId, feedback.getName(), feedback.getArguments(), scope);
        }
    }

    private void validateAlwaysDecision(String decision, String requestedScope) {
        if (!"always".equalsIgnoreCase(decision)) {
            return;
        }
        if (approvalRuleService == null) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "当前后端未启用 always 审批规则");
        }
        String effectiveScope = requestedScope == null ? ApprovalRuleService.SCOPE_SESSION : requestedScope;
        if (!ApprovalRuleService.SCOPE_SESSION.equalsIgnoreCase(effectiveScope)) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "always 审批只支持 session scope");
        }
    }

    private void resolveApprovalIfPossible(String threadId, String turnId, String canonicalDecision,
                                           String scope, String editedArgs) {
        if (approvalPersistenceService == null) {
            return;
        }
        approvalPersistenceService.resolvePending(
                threadId,
                turnId,
                canonicalDecision,
                scope,
                editedArgs,
                java.time.Instant.now());
    }

    /**
     * 读取原 turn 启动时保存的权限快照。
     *
     * <p>用户在审批弹窗停留期间可能又去设置页切换权限；恢复时必须沿用原 turn 的快照，
     * 否则同一轮工具执行会在审批前后使用两套不同策略。</p>
     */
    private AgentRunPolicy runPolicyForTurn(String turnId, BusinessIdentityScope scope) {
        if (turnPersistenceService == null) {
            return AgentRunPolicy.fromDefaults(agentLoopProperties);
        }
        return (scope.scoped() ? turnPersistenceService.findTurn(turnId, scope) : turnPersistenceService.findTurn(turnId))
                .map(this::runPolicyFromEntity)
                .orElseGet(() -> AgentRunPolicy.fromDefaults(agentLoopProperties));
    }

    /** 审批尚未消费前把策略读取异常收口为稳定协议错误，避免底层路径或 SQL 信息外泄。 */
    private AgentRunPolicy prepareRunPolicy(String turnId, BusinessIdentityScope scope) {
        try {
            return runPolicyForTurn(turnId, scope);
        } catch (RuntimeException failure) {
            throw new JsonRpcException(JsonRpcErrorCode.INTERNAL_ERROR, "审批恢复准备失败");
        }
    }

    private AgentRunPolicy runPolicyFromEntity(TurnEntity entity) {
        return AgentRunPolicy.fromSnapshots(entity.getSandboxMode(), entity.getApprovalPolicy(), agentLoopProperties);
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
}
