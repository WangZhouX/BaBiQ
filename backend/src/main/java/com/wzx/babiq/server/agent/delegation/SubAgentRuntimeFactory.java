package com.wzx.babiq.server.agent.delegation;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.LargeResultEvictionInterceptor;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.hook.BaBiQTokenUsageHook;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.interceptor.BaBiQStreamingTokenUsageInterceptor;
import com.wzx.babiq.server.interceptor.SpotlightingToolInterceptor;
import com.wzx.babiq.server.interceptor.ToolObservationInterceptor;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.tool.ToolRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子 Agent 运行时工厂。
 *
 * <p>BaBiQ 不重新实现 Agent runner，而是每次委派时构建一个 Spring AI Alibaba
 * {@link ReactAgent}，再用官方 {@link AgentTool#getFunctionToolCallback(ReactAgent)} 包成工具调用。
 * 本工厂只负责把 BaBiQ 的 cwd、沙箱、观测、UI 发射器和 delegation id 注入进去。</p>
 */
@Component
public class SubAgentRuntimeFactory {

    /** Spring AI Alibaba ToolContextHelper 使用的父 RunnableConfig key。 */
    public static final String AGENT_CONFIG_KEY = "_AGENT_CONFIG_";

    /** 序列化 AgentTool 输入 JSON。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatClientFactory chatClientFactory;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final AgentLoopProperties properties;
    private final BaBiQSandboxInterceptor sandboxInterceptor;
    private final ToolObservationInterceptor toolObservationInterceptor;
    private final SpotlightingToolInterceptor spotlightingInterceptor;
    private final BaBiQTokenUsageHook tokenUsageHook;
    private final BaBiQStreamingTokenUsageInterceptor streamingTokenUsageInterceptor;

    /**
     * 创建子 Agent 运行时工厂。
     *
     * @param chatClientFactory 统一解析 active provider 或指定 provider 的模型
     * @param toolRegistryProvider 懒加载工具注册表，避免 explorer 工具与 ToolRegistry 构造期循环依赖
     * @param properties agent-loop 配置，提供迭代上限和工具结果截断预算
     * @param sandboxInterceptor BaBiQ 现有沙箱拦截器，子 Agent 复用且强制 READ_ONLY
     * @param toolObservationInterceptor 工具观测拦截器，负责聚合子工具事件与落库归属
     * @param spotlightingInterceptor 工具结果 spotlighting 拦截器
     * @param tokenUsageHook turn 级 token hook，子 Agent 不 reset，累计到同一 turn
     * @param streamingTokenUsageInterceptor 流式 token usage 拦截器
     */
    public SubAgentRuntimeFactory(ChatClientFactory chatClientFactory,
                                  ObjectProvider<ToolRegistry> toolRegistryProvider,
                                  AgentLoopProperties properties,
                                  BaBiQSandboxInterceptor sandboxInterceptor,
                                  ToolObservationInterceptor toolObservationInterceptor,
                                  SpotlightingToolInterceptor spotlightingInterceptor,
                                  BaBiQTokenUsageHook tokenUsageHook,
                                  BaBiQStreamingTokenUsageInterceptor streamingTokenUsageInterceptor) {
        this.chatClientFactory = chatClientFactory;
        this.toolRegistryProvider = toolRegistryProvider;
        this.properties = properties;
        this.sandboxInterceptor = sandboxInterceptor;
        this.toolObservationInterceptor = toolObservationInterceptor;
        this.spotlightingInterceptor = spotlightingInterceptor;
        this.tokenUsageHook = tokenUsageHook;
        this.streamingTokenUsageInterceptor = streamingTokenUsageInterceptor;
    }

    /**
     * 调用子 Agent，并返回官方 AgentTool 产出的最终文本。
     *
     * @param spec 子 Agent 规格
     * @param input 父 Agent 委派给子 Agent 的自然语言任务
     * @param parentToolContext 父 Agent 调用 explorer 工具时传入的 ToolContext
     * @param delegationContext 当前委派上下文
     * @return 子 Agent 最终回复文本
     */
    public String delegate(BabiqAgentSpec spec,
                           String input,
                           ToolContext parentToolContext,
                           SubAgentDelegationContext delegationContext) {
        ToolContext childToolContext = withDelegationContext(parentToolContext, delegationContext);
        ToolCallback callback = AgentTool.getFunctionToolCallback(
                buildChildAgent(spec, childToolContext, null, new MemorySaver(), null));
        return callback.call(agentToolInput(input), childToolContext);
    }

    /**
     * 在父 ToolContext 中注入委派上下文，并把沙箱模式强制改为 READ_ONLY。
     *
     * <p>AgentTool 会从 ToolContext 中读取父 RunnableConfig；这里复制父 config 并补 metadata，
     * 使 AgentTool 创建 child config 时仍能保留 BaBiQ 的运行态边界。</p>
     */
    public static ToolContext withDelegationContext(ToolContext parentToolContext,
                                                    SubAgentDelegationContext delegationContext) {
        return withDelegationContext(parentToolContext, delegationContext, SandboxMode.READ_ONLY);
    }

    /**
     * 为 P6-2 流程节点注入委派上下文，并按节点审批后的沙箱模式保留权限快照。
     *
     * <p>P6-1 的 explorer 仍调用无 mode 重载并强制 READ_ONLY；流程节点只有在
     * run-before approve-once 完成后才会传入 WORKSPACE_WRITE 或 DANGER_FULL_ACCESS。
     * 这里仍只是写入上下文，真正边界由沙箱拦截器逐次判断。</p>
     */
    public static ToolContext withDelegationContext(ToolContext parentToolContext,
                                                    SubAgentDelegationContext delegationContext,
                                                    SandboxMode sandboxMode) {
        SandboxMode effectiveMode = sandboxMode == null ? SandboxMode.READ_ONLY : sandboxMode;
        Map<String, Object> context = parentToolContext == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(parentToolContext.getContext());
        context.put(SubAgentDelegationContext.METADATA_KEY, delegationContext);
        context.put(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, effectiveMode.name());

        Object candidate = context.get(AGENT_CONFIG_KEY);
        if (candidate instanceof RunnableConfig parentConfig) {
            RunnableConfig.Builder builder = RunnableConfig.builder(parentConfig)
                    .addMetadata(SubAgentDelegationContext.METADATA_KEY, delegationContext)
                    .addMetadata(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, effectiveMode.name());
            copyContextValueToMetadata(context, builder, BaBiQSandboxInterceptor.CONTEXT_CWD);
            copyContextValueToMetadata(context, builder, BaBiQSandboxInterceptor.CONTEXT_WRITABLE_ROOTS);
            copyContextValueToMetadata(context, builder, BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER);
            context.put(AGENT_CONFIG_KEY, builder.build());
        }
        return new ToolContext(context);
    }

    /**
     * 为 P6-2 流程节点构建可被官方 FlowAgent 编排的 ReactAgent。
     *
     * @param spec 节点对应的子 Agent 规格
     * @param childToolContext 已注入 delegation、cwd、沙箱和观测上下文的 ToolContext
     * @param outputKey 节点输出写入 SAA state 的 key；为空时保持 P6-1 兼容行为
     */
    public ReactAgent buildChildAgentForFlow(BabiqAgentSpec spec, ToolContext childToolContext, String outputKey) {
        return buildChildAgent(spec, childToolContext, outputKey, new MemorySaver(), null);
    }

    /**
     * 为 P6-3 supervisor StateGraph 构建团队成员 ReactAgent。
     *
     * <p>团队协作要求 supervisor graph 和所有 teammate 共享同一个 saver/config，
     * 这样官方图的检查点、中断恢复和状态读写都落在同一条运行链路里。该方法只开放
     * saver/config 注入点，其他横切层仍复用 BaBiQ 既有工具链。</p>
     *
     * @param spec 成员对应的子 Agent 规格
     * @param childToolContext 已注入 delegation、cwd、沙箱和观测上下文的 ToolContext
     * @param outputKey 成员输出写入 SAA state 的 key
     * @param sharedSaver 团队 supervisor 与所有成员共享的官方 checkpoint saver
     * @param compileConfig 团队 supervisor 与所有成员共享的官方编译配置
     */
    public ReactAgent buildChildAgentForTeam(BabiqAgentSpec spec,
                                             ToolContext childToolContext,
                                             String outputKey,
                                             BaseCheckpointSaver sharedSaver,
                                             CompileConfig compileConfig) {
        BaseCheckpointSaver effectiveSaver = sharedSaver == null ? new MemorySaver() : sharedSaver;
        return buildChildAgent(spec, childToolContext, outputKey, effectiveSaver, compileConfig);
    }

    private ReactAgent buildChildAgent(BabiqAgentSpec spec,
                                       ToolContext childToolContext,
                                       String outputKey,
                                       BaseCheckpointSaver saver,
                                       CompileConfig compileConfig) {
        ChatModel chatModel = chatClientFactory.resolveChatModel(spec.modelPolicy().providerId());
        ToolCallback[] childCallbacks = toolRegistryProvider.getObject().callbacksForNames(spec.toolNames());
        ModelCallLimitHook limitHook = ModelCallLimitHook.builder()
                .runLimit(properties.maxIterations())
                .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
                .build();
        LargeResultEvictionInterceptor evictionInterceptor = LargeResultEvictionInterceptor.builder()
                .toolTokenLimitBeforeEvict(properties.tools().output().maxTokens())
                .build();

        var builder = ReactAgent.builder()
                .name(spec.name())
                .description(spec.description())
                .model(chatModel)
                .systemPrompt(spec.systemPrompt())
                .tools(childCallbacks)
                .toolContext(childToolContext.getContext())
                .streamingInterceptors(streamingTokenUsageInterceptor)
                .interceptors(toolObservationInterceptor, sandboxInterceptor, spotlightingInterceptor, evictionInterceptor)
                .hooks(limitHook, tokenUsageHook)
                .saver(saver == null ? new MemorySaver() : saver);
        if (compileConfig != null) {
            builder.compileConfig(compileConfig);
        }
        if (outputKey != null && !outputKey.isBlank()) {
            builder.outputKey(outputKey);
        }
        return builder.build();
    }

    private static void copyContextValueToMetadata(Map<String, Object> context,
                                                   RunnableConfig.Builder builder,
                                                   String key) {
        Object value = context.get(key);
        if (value != null) {
            builder.addMetadata(key, value);
        }
    }

    private static String agentToolInput(String input) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("input", input == null ? "" : input));
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化子 Agent 委派输入", exception);
        }
    }
}
