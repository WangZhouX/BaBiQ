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
import com.wzx.babiq.server.model.ChatClientFactory;
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

    private final ChatClientFactory chatClientFactory;
    private final ToolRegistry toolRegistry;
    private final AgentLoopProperties properties;
    private final BaBiQSandboxInterceptor sandboxInterceptor;
    private final SpotlightingToolInterceptor spotlightingInterceptor;
    private final BaBiQTokenUsageHook tokenUsageHook;
    private final MemorySaver memorySaver = new MemorySaver();

    /**
     * 创建 ReAct 装配策略。
     *
     * @param chatClientFactory P1-2 provider 工厂
     * @param toolRegistry 工具注册表
     * @param properties Agent Loop 配置
     * @param sandboxInterceptor D31 沙箱拦截器
     * @param spotlightingInterceptor 工具结果不可信数据标注拦截器
     * @param tokenUsageHook token 累计 Hook
     */
    public ReActStrategy(ChatClientFactory chatClientFactory,
                         ToolRegistry toolRegistry,
                         AgentLoopProperties properties,
                         BaBiQSandboxInterceptor sandboxInterceptor,
                         SpotlightingToolInterceptor spotlightingInterceptor,
                         BaBiQTokenUsageHook tokenUsageHook) {
        this.chatClientFactory = chatClientFactory;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.sandboxInterceptor = sandboxInterceptor;
        this.spotlightingInterceptor = spotlightingInterceptor;
        this.tokenUsageHook = tokenUsageHook;
    }

    /**
     * 为一次 turn 构建 ReactAgent。
     *
     * @param providerId provider id，传 null 时使用 active provider
     * @param cwd 本轮 thread 工作目录
     * @param emitter 当前 turn 的协议 item 发射器
     * @return 已装配工具、Hook、Interceptor 和 MemorySaver 的 ReactAgent
     */
    public ReactAgent buildAgent(String providerId, String cwd, ItemEmitter emitter) {
        ChatModel chatModel = chatClientFactory.resolveChatModel(providerId);
        ToolCallback[] callbacks = toolRegistry.allCallbacks();
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put(BaBiQSandboxInterceptor.CONTEXT_CWD, cwd);
        toolContext.put(BaBiQSandboxInterceptor.CONTEXT_WRITABLE_ROOTS, stringify(properties.writableRoots()));
        toolContext.put(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter);

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

        tokenUsageHook.reset();
        return ReactAgent.builder()
                .name("babiq_agent")
                .model(chatModel)
                .systemPrompt(SystemPromptSecurityRule.PROMPT)
                .tools(callbacks)
                .toolContext(toolContext)
                .hooks(hitlHook, limitHook, tokenUsageHook)
                .interceptors(sandboxInterceptor, spotlightingInterceptor, evictionInterceptor)
                .saver(memorySaver)
                .build();
    }

    /**
     * 构建普通运行配置。
     *
     * @param threadId 业务线程 id
     * @return SAA RunnableConfig
     */
    public RunnableConfig buildConfig(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    /**
     * 构建 HITL 续跑配置。
     *
     * @param threadId 业务线程 id
     * @param metadata 用户审批后的反馈元数据
     * @return 带 human feedback 和 resume 标记的 RunnableConfig
     */
    public RunnableConfig buildResumeConfig(String threadId, InterruptionMetadata metadata) {
        return RunnableConfig.builder()
                .threadId(threadId)
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
        for (InterruptionMetadata.ToolFeedback feedback : metadata.toolFeedbacks()) {
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

    private List<String> stringify(List<Path> roots) {
        List<String> paths = new ArrayList<>();
        for (Path root : roots) {
            paths.add(root.toString());
        }
        return List.copyOf(paths);
    }
}
