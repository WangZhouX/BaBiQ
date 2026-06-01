package com.wzx.babiq.server.agent.flow;

import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.wzx.babiq.server.model.ChatClientFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

/**
 * P6-2 流程编排服务。
 *
 * <p>服务层不自研 DAG/调度器，而是把 BaBiQ 的冻结流程规格翻译为 Spring AI Alibaba
 * Agent Framework 官方的 {@link SequentialAgent}、{@link ParallelAgent} 和
 * {@link LlmRoutingAgent}。BaBiQ 只保留审批、沙箱、协议和持久化边界。</p>
 */
@Service
public class FlowOrchestrationService {

    /** 节点转官方 Agent 的工厂，生产环境复用 SubAgentRuntimeFactory。 */
    private final FlowNodeAgentFactory nodeAgentFactory;
    /** 运行前审批服务，负责冻结结构和解释范围。 */
    private final FlowApprovalService approvalService;
    /** RoutingAgent 需要的路由模型供应器；通常继承当前 provider 的 ChatModel。 */
    private final Supplier<ChatModel> routingModelSupplier;

    /**
     * 测试友好的构造器。
     */
    public FlowOrchestrationService(FlowNodeAgentFactory nodeAgentFactory,
                                    FlowApprovalService approvalService,
                                    ChatModel routingModel) {
        this.nodeAgentFactory = nodeAgentFactory;
        this.approvalService = approvalService;
        this.routingModelSupplier = () -> routingModel;
    }

    /**
     * 生产构造器：路由模型通过 ChatClientFactory 延迟解析，跟随当前 active provider。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public FlowOrchestrationService(DefaultFlowNodeAgentFactory nodeAgentFactory,
                                    FlowApprovalService approvalService,
                                    ChatClientFactory chatClientFactory) {
        this.nodeAgentFactory = nodeAgentFactory;
        this.approvalService = approvalService;
        this.routingModelSupplier = () -> chatClientFactory.resolveChatModel(null);
    }

    /**
     * 构建官方 FlowAgent，但不立即执行。
     *
     * <p>该方法是 P6-2 不重复造轮子的关键断言点：不同拓扑只映射到官方组件，
     * 子节点仍是 BaBiQ 包装后的 ReactAgent。</p>
     */
    public Agent buildOfficialFlowAgent(BabiqFlowSpec spec, ToolContext parentToolContext, String fallbackAgentName) {
        if (!spec.approved() || !spec.frozen()) {
            throw new IllegalStateException("流程必须先完成运行前整体审批并冻结");
        }
        ToolContext safeContext = parentToolContext == null ? new ToolContext(java.util.Map.of()) : parentToolContext;
        List<Agent> agents = spec.nodes().stream()
                .map(node -> nodeAgentFactory.create(node, safeContext))
                .toList();
        return switch (spec.topology()) {
            case SEQUENTIAL -> SequentialAgent.builder()
                    .name(spec.orchestrationId())
                    .description(spec.title())
                    .subAgents(agents)
                    .saver(new MemorySaver())
                    .build();
            case PARALLEL -> ParallelAgent.builder()
                    .name(spec.orchestrationId())
                    .description(spec.title())
                    .subAgents(agents)
                    .mergeOutputKey(spec.mergeOutputKey())
                    .mergeStrategy(new ParallelAgent.DefaultMergeStrategy())
                    .saver(new MemorySaver())
                    .build();
            case ROUTING -> LlmRoutingAgent.builder()
                    .name(spec.orchestrationId())
                    .description(spec.title())
                    .model(routingModelSupplier.get())
                    .fallbackAgent(fallbackAgentName == null || fallbackAgentName.isBlank()
                            ? spec.nodes().getFirst().name()
                            : fallbackAgentName)
                    .systemPrompt("你是 BaBiQ 流程路由器，只能在给定节点中选择最合适的一个执行。")
                    .instruction("{input}")
                    .subAgents(agents)
                    .saver(new MemorySaver())
                    .build();
        };
    }

    /**
     * 暴露审批服务，供工具层在实际执行前生成用户可读范围。
     */
    public FlowApprovalService approvalService() {
        return approvalService;
    }
}
