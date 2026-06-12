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
 * P6-2/P8 流程编排服务。
 *
 * <p>服务层不自研 DAG 或调度器，而是把 BaBiQ 的冻结流程规格递归翻译为 Spring AI Alibaba
 * Agent Framework 官方的 {@link SequentialAgent}、{@link ParallelAgent} 和 {@link LlmRoutingAgent}。
 * 叶子节点仍由 {@link FlowNodeAgentFactory} 创建 ReactAgent，审批、沙箱、协议和持久化边界保持在 BaBiQ。</p>
 */
@Service
public class FlowOrchestrationService {

    /** 节点转官方 Agent 的工厂，生产环境复用 SubAgentRuntimeFactory。 */
    private final FlowNodeAgentFactory nodeAgentFactory;
    /** 运行前审批服务，负责冻结结构和解释范围。 */
    private final FlowApprovalService approvalService;
    /** RoutingAgent 需要的路由模型供应器，通常继承当前 active provider。 */
    private final Supplier<ChatModel> routingModelSupplier;

    public FlowOrchestrationService(FlowNodeAgentFactory nodeAgentFactory,
                                    FlowApprovalService approvalService,
                                    ChatModel routingModel) {
        this.nodeAgentFactory = nodeAgentFactory;
        this.approvalService = approvalService;
        this.routingModelSupplier = () -> routingModel;
    }

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
     */
    public Agent buildOfficialFlowAgent(BabiqFlowSpec spec, ToolContext parentToolContext, String fallbackAgentName) {
        if (!spec.approved() || !spec.frozen()) {
            throw new IllegalStateException("流程必须先完成运行前整体审批并冻结");
        }
        ToolContext safeContext = parentToolContext == null ? new ToolContext(java.util.Map.of()) : parentToolContext;
        return compileGroup(spec.structure().root(), spec, safeContext, fallbackAgentName, true);
    }

    /**
     * 递归把结构树条目编译为官方 Agent：叶子复用现有节点工厂，组映射官方 FlowAgent。
     */
    private Agent compileEntry(BabiqFlowStructure.FlowEntry entry,
                               BabiqFlowSpec spec,
                               ToolContext toolContext,
                               String fallbackAgentName) {
        if (entry instanceof BabiqFlowStructure.FlowNodeRef ref) {
            BabiqFlowNode node = spec.node(ref.nodeId())
                    .orElseThrow(() -> new IllegalArgumentException("流程结构引用了不存在的节点: " + ref.nodeId()));
            return nodeAgentFactory.create(node, toolContext);
        }
        return compileGroup((BabiqFlowStructure.FlowGroup) entry, spec, toolContext, fallbackAgentName, false);
    }

    private Agent compileGroup(BabiqFlowStructure.FlowGroup group,
                               BabiqFlowSpec spec,
                               ToolContext toolContext,
                               String fallbackAgentName,
                               boolean root) {
        List<Agent> agents = group.children().stream()
                .map(child -> compileEntry(child, spec, toolContext, fallbackAgentName))
                .toList();
        String agentName = root ? spec.orchestrationId() : group.groupId();
        String description = root ? spec.title() : spec.title() + " / " + group.groupId();
        return switch (group.topology()) {
            case SEQUENTIAL -> SequentialAgent.builder()
                    .name(agentName)
                    .description(description)
                    .subAgents(agents)
                    .saver(new MemorySaver())
                    .build();
            case PARALLEL -> ParallelAgent.builder()
                    .name(agentName)
                    .description(description)
                    .subAgents(agents)
                    .mergeOutputKey(root ? spec.mergeOutputKey() : group.groupId() + "_output")
                    .mergeStrategy(new ParallelAgent.DefaultMergeStrategy())
                    .saver(new MemorySaver())
                    .build();
            case ROUTING -> LlmRoutingAgent.builder()
                    .name(agentName)
                    .description(description)
                    .model(routingModelSupplier.get())
                    .fallbackAgent(resolveFallbackAgentName(group, spec, fallbackAgentName, root))
                    .systemPrompt("你是 BaBiQ 流程路由器，只能在给定节点中选择最合适的一个执行。")
                    .instruction("{input}")
                    .subAgents(agents)
                    .saver(new MemorySaver())
                    .build();
        };
    }

    private String resolveFallbackAgentName(BabiqFlowStructure.FlowGroup group,
                                            BabiqFlowSpec spec,
                                            String fallbackAgentName,
                                            boolean root) {
        if (root && fallbackAgentName != null && !fallbackAgentName.isBlank()) {
            return fallbackAgentName;
        }
        BabiqFlowStructure.FlowEntry first = group.children().getFirst();
        if (first instanceof BabiqFlowStructure.FlowNodeRef ref) {
            return spec.node(ref.nodeId())
                    .map(BabiqFlowNode::name)
                    .orElse(ref.nodeId());
        }
        return ((BabiqFlowStructure.FlowGroup) first).groupId();
    }

    public FlowApprovalService approvalService() {
        return approvalService;
    }
}
