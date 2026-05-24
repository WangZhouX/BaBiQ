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
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.hook.BaBiQTokenUsageHook;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.interceptor.SpotlightingToolInterceptor;
import com.wzx.babiq.server.interceptor.ToolObservationInterceptor;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.approval.ApprovalRuleService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import com.wzx.babiq.server.security.SystemPromptSecurityRule;
import com.wzx.babiq.server.tool.ToolRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    /** Spring AI Alibaba Graph 的内存检查点，HITL 暂停和恢复需要依赖它保存图状态。 */
    private final MemorySaver memorySaver = new MemorySaver();
    /** Always 规则服务，用于把重复审批自动转成 approve。 */
    private final ApprovalRuleService approvalRuleService;
    /** turn 持久化服务，HITL 进入等待态时用它同步数据库状态。 */
    private final TurnPersistenceService turnPersistenceService;

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
     * @param approvalRuleService Always 审批规则服务
     */
    public ReActStrategy(ChatClientFactory chatClientFactory,
                         ToolRegistry toolRegistry,
                         AgentLoopProperties properties,
                         BaBiQSandboxInterceptor sandboxInterceptor,
                         ToolObservationInterceptor toolObservationInterceptor,
                         SpotlightingToolInterceptor spotlightingInterceptor,
                         BaBiQTokenUsageHook tokenUsageHook,
                         ApprovalRuleService approvalRuleService,
                         TurnPersistenceService turnPersistenceService) {
        this.chatClientFactory = chatClientFactory;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.sandboxInterceptor = sandboxInterceptor;
        this.toolObservationInterceptor = toolObservationInterceptor;
        this.spotlightingInterceptor = spotlightingInterceptor;
        this.tokenUsageHook = tokenUsageHook;
        this.approvalRuleService = approvalRuleService;
        this.turnPersistenceService = turnPersistenceService;
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
        ChatModel chatModel = chatClientFactory.resolveChatModel(providerId);
        ToolCallback[] callbacks = toolRegistry.allCallbacks();

        // toolContext 是 SAA 在工具调用和拦截器之间传递上下文的 Map。
        // BaBiQ 把 cwd、额外可写根、emitter 和观测上下文都放进去，避免工具自己依赖全局状态。
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put(BaBiQSandboxInterceptor.CONTEXT_CWD, cwd);
        toolContext.put(BaBiQSandboxInterceptor.CONTEXT_WRITABLE_ROOTS, stringify(properties.writableRoots()));
        toolContext.put(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter);
        toolContext.put(TurnObservationContext.METADATA_KEY, context);

        // D23：写类工具声明式触发 SAA 原生 HumanInTheLoopHook，不手写阻塞审批状态机。
        HumanInTheLoopHook hitlHook = HumanInTheLoopHook.builder()
                .approvalOn("write_file", ToolConfig.builder().description("写入文件需要确认").build())
                .approvalOn("exec_shell", ToolConfig.builder().description("执行 Shell 命令需要确认").build())
                .approvalOn("apply_patch", ToolConfig.builder().description("应用补丁需要确认").build())
                .build();
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
        return ReactAgent.builder()
                .name("babiq_agent")
                .model(chatModel)
                .systemPrompt(SystemPromptSecurityRule.PROMPT)
                .tools(callbacks)
                .toolContext(toolContext)
                .hooks(hitlHook, limitHook, tokenUsageHook)
                .interceptors(sandboxInterceptor, toolObservationInterceptor, spotlightingInterceptor, evictionInterceptor)
                .saver(memorySaver)
                .build();
    }

    /**
     * 解析本轮会使用的模型名称，主要供日志和 TurnSummary 展示。
     */
    public String resolveModelName(String providerId) {
        return chatClientFactory.resolveModelName(providerId);
    }

    /**
     * 构建普通运行配置。
     *
     * @param threadId 业务线程 id
     * @return SAA RunnableConfig
     */
    public RunnableConfig buildConfig(String threadId, TurnObservationContext context) {
        return RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata(TurnObservationContext.METADATA_KEY, context)
                .build();
    }

    /**
     * 构建 HITL 续跑配置。
     *
     * @param threadId 业务线程 id
     * @param metadata 用户审批后的反馈元数据
     * @return 带 human feedback 和 resume 标记的 RunnableConfig
     */
    public RunnableConfig buildResumeConfig(String threadId,
                                            InterruptionMetadata metadata,
                                            TurnObservationContext context) {
        return RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata(TurnObservationContext.METADATA_KEY, context)
                .addHumanFeedback(metadata)
                .resume()
                .build();
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
     * 把配置里的 Path 列表转换为字符串列表，便于放入 SAA toolContext。
     */
    private List<String> stringify(List<Path> roots) {
        List<String> paths = new ArrayList<>();
        for (Path root : roots) {
            paths.add(root.toString());
        }
        return List.copyOf(paths);
    }
}
