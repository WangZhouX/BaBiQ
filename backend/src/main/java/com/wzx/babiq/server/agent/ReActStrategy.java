package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.LargeResultEvictionInterceptor;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.capability.CapabilityExposurePlan;
import com.wzx.babiq.server.capability.CapabilityExposurePlanner;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.hook.BaBiQTokenUsageHook;
import com.wzx.babiq.server.hook.ResumeJumpCleanupHook;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.interceptor.BaBiQStreamingTokenUsageInterceptor;
import com.wzx.babiq.server.interceptor.SpotlightingToolInterceptor;
import com.wzx.babiq.server.interceptor.ToolObservationInterceptor;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.approval.ApprovalRuleService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import com.wzx.babiq.server.security.SystemPromptSecurityRule;
import com.wzx.babiq.server.tool.ToolRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ReActAgent 装配策略。
 *
 * <p>该类把 P1-3a 的横切能力集中挂到 SAA ReactAgent 上：D21 的模型调用限流、
 * D19 的大工具输出截断、D23 的 HumanInTheLoopHook、D31 的沙箱拦截器，以及 BaBiQ
 * 自己的 token 累计 Hook。AgentLoop 只调用它，不关心这些装配细节。</p>
 */
@Component
public class ReActStrategy {

    /** 根据当前 Provider 选择并缓存 Spring AI ChatClient，ReActAgent 最终通过它调用模型。 */
    private final ChatClientFactory chatClientFactory;
    /** 汇总 BaBiQ 暴露给 Agent 的所有工具，并转换成 Spring AI ToolCallback。 */
    private final ToolRegistry toolRegistry;
    /** agent-loop 配置项，控制系统提示词、最大步数、上下文窗口、审批策略等运行参数。 */
    private final AgentLoopProperties properties;
    /** 工具执行前的沙箱拦截器，负责限制写文件、命令执行和补丁应用的可访问路径。 */
    private final BaBiQSandboxInterceptor sandboxInterceptor;
    /** 工具执行后的观测拦截器，用于统计每个 turn 的工具调用次数和工具名称。 */
    private final ToolObservationInterceptor toolObservationInterceptor;
    /** 工具输出安全拦截器，用 spotlighting 标记外部内容，降低 prompt injection 风险。 */
    private final SpotlightingToolInterceptor spotlightingInterceptor;
    /** Spring AI Alibaba token hook，用于从模型响应里累计 prompt/completion token。 */
    private final BaBiQTokenUsageHook tokenUsageHook;
    /** 审批恢复后清理一次性 jump_to 标记，避免工具执行完以后继续被强制路由回工具节点。 */
    private final ResumeJumpCleanupHook resumeJumpCleanupHook;
    /** 流式模型 token 拦截器，用于在不关闭 streaming 的前提下读取最终 usage。 */
    private final BaBiQStreamingTokenUsageInterceptor streamingTokenUsageInterceptor;
    /** Spring AI Alibaba Graph 的内存检查点，HITL 暂停和恢复需要依赖它保存图状态。 */
    private final MemorySaver memorySaver = new MemorySaver();
    /** Always 规则服务，用于把重复审批自动转成 approve。 */
    private final ApprovalRuleService approvalRuleService;
    /** turn 持久化服务，HITL 进入等待态时用它同步数据库状态。 */
    private final TurnPersistenceService turnPersistenceService;
    /** P3-5 能力暴露规划器；为空时退回 P3-4 全量工具行为。 */
    private final ObjectProvider<CapabilityExposurePlanner> capabilityExposurePlannerProvider;

    /**
     * 创建 ReAct 装配策略。
     *
     * @param chatClientFactory P1-2 provider 工厂
     * @param toolRegistry 工具注册表
     * @param properties Agent Loop 配置
     * @param sandboxInterceptor D31 沙箱拦截器
     * @param toolObservationInterceptor 工具调用观测拦截器
     * @param spotlightingInterceptor 工具结果不可信数据标注拦截器
     * @param tokenUsageHook token 累计 Hook
     * @param streamingTokenUsageInterceptor 流式 token usage 拦截器
     * @param approvalRuleService Always 审批规则服务
     */
    public ReActStrategy(ChatClientFactory chatClientFactory,
                         ToolRegistry toolRegistry,
                         AgentLoopProperties properties,
                         BaBiQSandboxInterceptor sandboxInterceptor,
                         ToolObservationInterceptor toolObservationInterceptor,
                         SpotlightingToolInterceptor spotlightingInterceptor,
                         BaBiQTokenUsageHook tokenUsageHook,
                         ResumeJumpCleanupHook resumeJumpCleanupHook,
                         BaBiQStreamingTokenUsageInterceptor streamingTokenUsageInterceptor,
                         ApprovalRuleService approvalRuleService,
                         TurnPersistenceService turnPersistenceService) {
        this(chatClientFactory, toolRegistry, properties, sandboxInterceptor, toolObservationInterceptor,
                spotlightingInterceptor, tokenUsageHook, resumeJumpCleanupHook, streamingTokenUsageInterceptor,
                approvalRuleService, turnPersistenceService, null);
    }

    /**
     * 创建带 P3-5 能力暴露规划器的 ReAct 装配策略。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ReActStrategy(ChatClientFactory chatClientFactory,
                         ToolRegistry toolRegistry,
                         AgentLoopProperties properties,
                         BaBiQSandboxInterceptor sandboxInterceptor,
                         ToolObservationInterceptor toolObservationInterceptor,
                         SpotlightingToolInterceptor spotlightingInterceptor,
                         BaBiQTokenUsageHook tokenUsageHook,
                         ResumeJumpCleanupHook resumeJumpCleanupHook,
                         BaBiQStreamingTokenUsageInterceptor streamingTokenUsageInterceptor,
                         ApprovalRuleService approvalRuleService,
                         TurnPersistenceService turnPersistenceService,
                         ObjectProvider<CapabilityExposurePlanner> capabilityExposurePlannerProvider) {
        this.chatClientFactory = chatClientFactory;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.sandboxInterceptor = sandboxInterceptor;
        this.toolObservationInterceptor = toolObservationInterceptor;
        this.spotlightingInterceptor = spotlightingInterceptor;
        this.tokenUsageHook = tokenUsageHook;
        this.resumeJumpCleanupHook = resumeJumpCleanupHook;
        this.streamingTokenUsageInterceptor = streamingTokenUsageInterceptor;
        this.approvalRuleService = approvalRuleService;
        this.turnPersistenceService = turnPersistenceService;
        this.capabilityExposurePlannerProvider = capabilityExposurePlannerProvider;
    }

    /**
     * 为一次 turn 构建 ReactAgent。
     *
     * @param providerId provider id，传 null 时使用 active provider
     * @param cwd 本轮 thread 工作目录
     * @param emitter 当前 turn 的协议 item 发射器
     * @param context 当前 turn 的观测上下文
     * @return 已装配工具、Hook、Interceptor 和 MemorySaver 的 ReactAgent
     */
    public ReactAgent buildAgent(String providerId, String cwd, ItemEmitter emitter, TurnObservationContext context) {
        return buildAgent(providerId, cwd, emitter, context, defaultRunPolicy());
    }

    /**
     * 为一次 turn 构建 ReactAgent，并使用本轮权限快照装配 HITL 与沙箱上下文。
     *
     * @param providerId provider id，传 null 时使用 active provider
     * @param cwd 本轮 thread 工作目录
     * @param emitter 当前 turn 的协议 item 发射器
     * @param context 当前 turn 的观测上下文
     * @param runPolicy turn/start 时固定下来的沙箱和审批策略
     * @return 已装配工具、Hook、Interceptor 和 MemorySaver 的 ReactAgent
     */
    public ReactAgent buildAgent(String providerId, String cwd, ItemEmitter emitter,
                                 TurnObservationContext context, AgentRunPolicy runPolicy) {
        return buildAgent(providerId, cwd, emitter, context, runPolicy, null);
    }

    /**
     * 为一次 turn 构建 ReactAgent，并按 P3-5 能力计划筛选模型可见工具。
     */
    public ReactAgent buildAgent(String providerId, String cwd, ItemEmitter emitter,
                                 TurnObservationContext context, AgentRunPolicy runPolicy,
                                 CapabilityExposurePlan exposurePlan) {
        ChatModel chatModel = chatClientFactory.resolveChatModel(providerId);
        ToolCallback[] callbacks = exposurePlan == null
                ? toolRegistry.allCallbacks()
                : toolRegistry.callbacksForNames(exposurePlan.visibleToolNames());
        AgentRunPolicy effectivePolicy = runPolicy == null ? defaultRunPolicy() : runPolicy;

        // toolContext 是 SAA 在工具调用和拦截器之间传递上下文的 Map。
        // BaBiQ 把 cwd、额外可写根、emitter 和观测上下文都放进去，避免工具自己依赖全局状态。
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put(BaBiQSandboxInterceptor.CONTEXT_CWD, cwd);
        toolContext.put(BaBiQSandboxInterceptor.CONTEXT_WRITABLE_ROOTS, stringify(properties.writableRoots()));
        toolContext.put(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter);
        toolContext.put(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, effectivePolicy.sandboxMode().name());
        toolContext.put(TurnObservationContext.METADATA_KEY, context);

        ModelCallLimitHook limitHook = ModelCallLimitHook.builder()
                .runLimit(properties.maxIterations())
                .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
                .build();
        LargeResultEvictionInterceptor evictionInterceptor = LargeResultEvictionInterceptor.builder()
                .toolTokenLimitBeforeEvict(properties.tools().output().maxTokens())
                .excludeTool("write_file")
                .excludeTool("apply_patch")
                .build();

        // tokenUsageHook 是按 turn 累计的，构建新 agent 前必须清空上一轮残留。
        tokenUsageHook.reset();
        var builder = ReactAgent.builder()
                .name("babiq_agent")
                .model(chatModel)
                .systemPrompt(SystemPromptSecurityRule.PROMPT)
                .tools(callbacks)
                .toolContext(toolContext)
                .streamingInterceptors(streamingTokenUsageInterceptor)
                .interceptors(sandboxInterceptor, toolObservationInterceptor, spotlightingInterceptor, evictionInterceptor)
                .saver(memorySaver);
        if (effectivePolicy.approvalPolicy() == ApprovalPolicy.NEVER) {
            builder.hooks(limitHook, resumeJumpCleanupHook, tokenUsageHook);
        } else {
            // ON_REQUEST 和暂未单独实现的 ON_FAILURE 都保持需要确认的保守行为，避免权限设置解析异常时静默放行。
            // ON_REQUEST 和暂未单独实现的 ON_FAILURE 保持高风险工具确认；
            // ALWAYS 则把本轮可见工具全部挂进 HITL，确保设置页切换真正影响 Agent。
            builder.hooks(buildHitlHook(effectivePolicy.approvalPolicy()), limitHook, resumeJumpCleanupHook, tokenUsageHook);
        }
        return builder.build();
    }

    /**
     * 解析本轮会使用的模型名称，主要供日志和 TurnSummary 展示。
     */
    public String resolveModelName(String providerId) {
        return chatClientFactory.resolveModelName(providerId);
    }

    /**
     * 解析本轮 Provider 的上下文窗口，用于 P3-2 运行时快照预算。
     *
     * @param providerId provider id；为空时使用 active provider
     * @return 有效上下文窗口 token 数
     */
    public int resolveContextWindow(String providerId) {
        return chatClientFactory.resolveContextWindow(providerId);
    }

    /**
     * 返回当前候选工具 callback，用于上下文 envelope 生成能力目录摘要。
     *
     * @return Spring AI ToolCallback 数组
     */
    public ToolCallback[] currentToolCallbacks() {
        return toolRegistry.allCallbacks();
    }

    /**
     * 返回当前计划内可见工具 callback，用于上下文 envelope 生成能力目录摘要。
     */
    public ToolCallback[] currentToolCallbacks(CapabilityExposurePlan exposurePlan) {
        return exposurePlan == null ? currentToolCallbacks() : toolRegistry.callbacksForNames(exposurePlan.visibleToolNames());
    }

    /**
     * 生成本轮能力暴露计划；缺少 P3-5 服务时返回 null，保持旧行为。
     */
    public CapabilityExposurePlan planCapabilities(String threadId, String turnId) {
        CapabilityExposurePlanner planner = capabilityExposurePlannerProvider == null
                ? null
                : capabilityExposurePlannerProvider.getIfAvailable();
        return planner == null ? null : planner.plan(threadId, turnId);
    }

    /**
     * 构建普通运行配置。
     *
     * @param threadId 业务线程 id
     * @return SAA RunnableConfig
     */
    public RunnableConfig buildConfig(String threadId, String cwd, ItemEmitter emitter, TurnObservationContext context) {
        return buildConfig(threadId, cwd, emitter, context, defaultRunPolicy());
    }

    /**
     * 构建普通运行配置，并把本轮权限快照写入 metadata。
     *
     * <p>SAA 工具节点恢复上下文时会读取 RunnableConfig metadata。这里和 buildAgent 的
     * toolContext 同时写入沙箱模式，确保普通执行和审批恢复都使用同一份 turn 快照。</p>
     */
    public RunnableConfig buildConfig(String threadId, String cwd, ItemEmitter emitter,
                                      TurnObservationContext context, AgentRunPolicy runPolicy) {
        AgentRunPolicy effectivePolicy = runPolicy == null ? defaultRunPolicy() : runPolicy;
        RunnableConfig.Builder builder = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata(TurnObservationContext.METADATA_KEY, context)
                .addMetadata(BaBiQSandboxInterceptor.CONTEXT_WRITABLE_ROOTS, stringify(properties.writableRoots()))
                .addMetadata(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, effectivePolicy.sandboxMode().name());
        if (cwd != null && !cwd.isBlank()) {
            builder.addMetadata(BaBiQSandboxInterceptor.CONTEXT_CWD, cwd);
        }
        if (emitter != null) {
            builder.addMetadata(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter);
        }
        return builder.build();
    }

    /**
     * 兼容旧测试入口；真实 AgentLoop 必须调用带 cwd / emitter 的重载。
     */
    public RunnableConfig buildConfig(String threadId, TurnObservationContext context) {
        return buildConfig(threadId, null, null, context);
    }

    /**
     * 构建 HITL 续跑配置。
     *
     * <p>Bug 记录（2026-05-25）：这里严格按 Spring AI Alibaba ReactAgent HITL 官方示例，
     * 只写入真实 {@link InterruptionMetadata}，不再额外调用 {@code resume()}。
     * {@code resume()} 只会写入占位 human feedback，容易在维护时因为调用顺序变化覆盖真实审批反馈。</p>
     *
     * @param threadId 业务线程 id
     * @param metadata 用户审批后的反馈元数据
     * @return 带 human feedback 的 RunnableConfig
     */
    public RunnableConfig buildResumeConfig(String threadId,
                                            InterruptionMetadata metadata,
                                            String cwd,
                                            ItemEmitter emitter,
                                            TurnObservationContext context) {
        return buildResumeConfig(threadId, metadata, cwd, emitter, context, defaultRunPolicy());
    }

    /**
     * 构建 HITL 续跑配置，并携带原 turn 的权限快照。
     *
     * @param threadId 业务线程 id
     * @param metadata 用户审批后的真实 HITL 反馈元数据
     * @param cwd 原 turn 工作目录
     * @param emitter 当前 WebSocket item 发射器
     * @param context 本轮观测上下文
     * @param runPolicy 原 turn 启动时保存下来的运行策略
     * @return 带 human feedback 和权限 metadata 的 RunnableConfig
     */
    public RunnableConfig buildResumeConfig(String threadId,
                                            InterruptionMetadata metadata,
                                            String cwd,
                                            ItemEmitter emitter,
                                            TurnObservationContext context,
                                            AgentRunPolicy runPolicy) {
        AgentRunPolicy effectivePolicy = runPolicy == null ? defaultRunPolicy() : runPolicy;
        RunnableConfig.Builder builder = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata(TurnObservationContext.METADATA_KEY, context)
                .addMetadata(BaBiQSandboxInterceptor.CONTEXT_WRITABLE_ROOTS, stringify(properties.writableRoots()))
                .addMetadata(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, effectivePolicy.sandboxMode().name())
                // 2026-05-25 Bug 修复记录：ReactAgent 恢复只需要真实审批反馈。
                // 官方示例使用 addHumanFeedback(metadata)；额外 resume() 的占位标记没有业务信息，
                // 对 BaBiQ 这种 HITL 恢复反而增加“占位值覆盖真实 InterruptionMetadata”的风险。
                .addHumanFeedback(metadata);
        if (cwd != null && !cwd.isBlank()) {
            builder.addMetadata(BaBiQSandboxInterceptor.CONTEXT_CWD, cwd);
        }
        if (emitter != null) {
            builder.addMetadata(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter);
        }
        return builder.build();
    }

    /**
     * 兼容旧测试入口；真实 approval/respond 恢复必须调用带 cwd / emitter 的重载。
     */
    public RunnableConfig buildResumeConfig(String threadId,
                                            InterruptionMetadata metadata,
                                            TurnObservationContext context) {
        return buildResumeConfig(threadId, metadata, null, null, context);
    }

    /**
     * 从 NodeOutput 的 state 中提取最终 AssistantMessage。
     *
     * @param node SAA 节点输出
     * @return 最后一条 AssistantMessage
     */
    public AssistantMessage extractAssistantMessage(NodeOutput node) {
        List<?> messages = node.state().value("messages", List.of());
        // SAA state 里 messages 是顺序列表，最后一条 AssistantMessage 才是本轮最终可见回答。
        for (int index = messages.size() - 1; index >= 0; index--) {
            Object message = messages.get(index);
            if (message instanceof AssistantMessage assistantMessage) {
                return assistantMessage;
            }
        }
        throw new IllegalStateException("NodeOutput 中没有 AssistantMessage");
    }

    /**
     * 将 HITL 中断元数据展开为 approval/request notification。
     *
     * @param turn 当前 turn
     * @param emitter 当前 WebSocket 发射器
     * @param metadata SAA 中断元数据
     * @throws Exception notification 发送失败时抛出
     */
    public void emitApprovalRequests(Turn turn, ItemEmitter emitter, InterruptionMetadata metadata) throws Exception {
        turnPersistenceService.markWaitingApproval(turn.id());
        for (InterruptionMetadata.ToolFeedback feedback : metadata.toolFeedbacks()) {
            // P1 阶段按一个 toolFeedback 生成一个 approval/request，后续如果 SAA 返回 batch 再扩展 UI。
            ApprovalRequestPayload payload = new ApprovalRequestPayload(
                    turn.threadId(),
                    turn.id(),
                    "appr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                    feedback.getName(),
                    feedback.getArguments() == null ? "" : feedback.getArguments(),
                    feedback.getDescription());
            emitter.emitApprovalRequest(payload);
        }
    }

    /**
     * 如果所有工具调用都命中 session always 规则，就构造自动 approve 的 HITL 反馈。
     *
     * @param threadId 当前会话 id
     * @param metadata SAA 返回的 HITL 中断元数据
     * @return 命中时返回 approved feedback；否则为空，继续展示审批弹窗
     */
    public Optional<InterruptionMetadata> autoApprovedFeedback(String threadId, InterruptionMetadata metadata) {
        if (metadata.toolFeedbacks().isEmpty()) {
            return Optional.empty();
        }
        boolean allAllowed = metadata.toolFeedbacks().stream()
                .allMatch(feedback -> approvalRuleService.isAlwaysAllowed(
                        threadId,
                        feedback.getName(),
                        feedback.getArguments()));
        if (!allAllowed) {
            return Optional.empty();
        }

        InterruptionMetadata.Builder builder = InterruptionMetadata.builder(metadata);
        builder.toolFeedbacks(List.of());
        for (InterruptionMetadata.ToolFeedback feedback : metadata.toolFeedbacks()) {
            builder.addToolFeedback(InterruptionMetadata.ToolFeedback.builder(feedback)
                    .result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED)
                    .build());
        }
        return Optional.of(builder.build());
    }

    /**
     * 返回 yml 静态配置对应的兼容运行策略。
     *
     * <p>真实 turn/start 会传入 AppSettings 快照；该方法只服务旧测试入口和缺失历史快照时的回退。</p>
     */
    public AgentRunPolicy defaultRunPolicy() {
        return AgentRunPolicy.fromDefaults(properties);
    }

    /**
     * 构建 SAA 原生 HITL hook。
     *
     * <p>只有审批策略不是 NEVER 时才安装该 hook；因此“从不询问”会真正影响后端 Agent，
     * 而不只是改变桌面端按钮高亮。</p>
     */
    private HumanInTheLoopHook buildHitlHook(ApprovalPolicy approvalPolicy) {
        HumanInTheLoopHook.Builder hitlBuilder = HumanInTheLoopHook.builder();
        for (String toolName : approvalToolNamesFor(approvalPolicy)) {
            hitlBuilder.approvalOn(toolName, ToolConfig.builder().description(approvalDescription(toolName, approvalPolicy)).build());
        }
        return hitlBuilder.build();
    }

    /**
     * 根据审批策略计算本轮需要 Human-in-the-loop 的工具名。
     *
     * <p>这个方法是设置页“按需询问 / 全部询问 / 永不询问”的后端事实源：
     * UI 保存策略后，下一轮 turn 构建 ReactAgent 时会用同一组规则挂载官方 HITL Hook。</p>
     *
     * @param approvalPolicy 本轮审批策略
     * @return 需要审批的工具名，按声明顺序去重
     */
    List<String> approvalToolNamesFor(ApprovalPolicy approvalPolicy) {
        if (approvalPolicy == ApprovalPolicy.NEVER) {
            return List.of();
        }
        if (approvalPolicy == ApprovalPolicy.ALWAYS) {
            return toolRegistry.names().stream()
                    // update_plan 只更新桌面端进度 item，不触碰文件系统、网络或外部 MCP，因此即使“全部询问”也不打断模型。
                    .filter(toolName -> !"update_plan".equals(toolName))
                    .toList();
        }

        Set<String> names = new LinkedHashSet<>();
        names.add("write_file");
        names.add("exec_shell");
        names.add("apply_patch");
        names.add("orchestrate_flow");
        names.add("coordinate_team");
        for (String toolName : toolRegistry.names()) {
            if (toolName.startsWith("mcp.")) {
                // MCP 工具来自外部 server，即使是读操作也先让用户确认，避免第三方工具越权访问本机数据。
                names.add(toolName);
            }
        }
        return List.copyOf(names);
    }

    /**
     * 给 HITL 弹窗提供工具审批说明，保持高风险工具和全量审批的语义可读。
     */
    private String approvalDescription(String toolName, ApprovalPolicy approvalPolicy) {
        if (approvalPolicy == ApprovalPolicy.ALWAYS) {
            return "全部询问策略要求确认每一次工具调用";
        }
        if ("write_file".equals(toolName)) {
            return "写入文件需要确认";
        }
        if ("exec_shell".equals(toolName)) {
            return "执行 Shell 命令需要确认";
        }
        if ("apply_patch".equals(toolName)) {
            return "应用补丁需要确认";
        }
        if ("orchestrate_flow".equals(toolName)) {
            return "运行多 Agent 流程需要先确认节点、工具和写入范围";
        }
        if ("coordinate_team".equals(toolName)) {
            return "运行团队协作需要先确认成员、工具、轮数和写入范围";
        }
        return "调用 MCP 工具需要确认";
    }

    private HumanInTheLoopHook buildHitlHook() {
        HumanInTheLoopHook.Builder hitlBuilder = HumanInTheLoopHook.builder()
                .approvalOn("write_file", ToolConfig.builder().description("写入文件需要确认").build())
                .approvalOn("exec_shell", ToolConfig.builder().description("执行 Shell 命令需要确认").build())
                .approvalOn("apply_patch", ToolConfig.builder().description("应用补丁需要确认").build());
        for (String toolName : toolRegistry.names()) {
            if (toolName.startsWith("mcp.")) {
                // MCP 工具来自外部 server，即使只是读操作也必须先让用户确认，防止第三方工具越权访问本机数据。
                hitlBuilder.approvalOn(toolName, ToolConfig.builder().description("调用 MCP 工具需要确认").build());
            }
        }
        return hitlBuilder.build();
    }

    /**
     * 把配置里的 Path 列表转换为字符串列表，便于放入 SAA toolContext。
     */
    private List<String> stringify(List<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (Path root : roots) {
            paths.add(root.toString());
        }
        return List.copyOf(paths);
    }
}
