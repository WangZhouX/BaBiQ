package com.wzx.babiq.server.tool.impl;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.agent.delegation.SubAgentRuntimeFactory;
import com.wzx.babiq.server.agent.flow.BabiqFlowNode;
import com.wzx.babiq.server.agent.flow.BabiqFlowSpec;
import com.wzx.babiq.server.agent.flow.BabiqFlowTopology;
import com.wzx.babiq.server.agent.flow.FlowApprovalService;
import com.wzx.babiq.server.agent.flow.FlowOrchestrationService;
import com.wzx.babiq.server.agent.flow.OrchestrationNodeRecord;
import com.wzx.babiq.server.agent.flow.OrchestrationRecord;
import com.wzx.babiq.server.agent.flow.OrchestrationRepository;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.OrchestrationItem;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.tool.Tool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * P6-2 多 Agent 流程编排工具。
 *
 * <p>主 Agent 通过该工具一次性提交冻结流程规格；由于 `orchestrate_flow`
 * 被 ReActStrategy 挂到 HITL，高风险写流程会在运行前整体弹窗确认。工具内部不再逐工具暂停，
 * 但每个子节点仍复用 BaBiQ 沙箱和工具观测链路。</p>
 */
@Component
public class FlowOrchestrationTool implements Tool {

    /** 协议 item 类型。 */
    private static final String TYPE = "orchestration";

    /** 官方 FlowAgent 薄封装服务。 */
    private final FlowOrchestrationService orchestrationService;
    /** 流程运行仓储，用于右侧详情和审计。 */
    private final OrchestrationRepository repository;
    /** 运行前审批范围服务。 */
    private final FlowApprovalService approvalService;

    /**
     * 创建流程编排工具。
     */
    public FlowOrchestrationTool(FlowOrchestrationService orchestrationService,
                                 OrchestrationRepository repository,
                                 FlowApprovalService approvalService) {
        this.orchestrationService = orchestrationService;
        this.repository = repository;
        this.approvalService = approvalService;
    }

    @Override
    public String name() {
        return "orchestrate_flow";
    }

    /**
     * 运行一个顺序/并行/路由流程。
     *
     * @param task 流程整体任务
     * @param topology 拓扑，sequential、parallel 或 routing
     * @param nodes 节点列表；为空时创建一个只读 explorer 节点
     * @param toolContext Spring AI 工具上下文，携带 cwd、沙箱、emitter、turn 观测信息
     * @return 给父 Agent 的流程结果摘要
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "orchestrate_flow",
            description = """
                    Run a frozen multi-agent flow using Spring AI Alibaba SequentialAgent, ParallelAgent or LlmRoutingAgent.
                    运行多 Agent 流程、编排、顺序、并行、路由、多节点任务。If any node writes files or uses high-risk tools,
                    this tool itself must be approved before the flow starts; child nodes still obey BaBiQ sandbox limits.
                    """)
    public String orchestrateFlow(
            @ToolParam(description = "流程整体任务，必须说明目标和完成标准") String task,
            @ToolParam(description = "拓扑：sequential、parallel 或 routing", required = false) String topology,
            @ToolParam(description = "节点列表；每个节点包含 name/displayName/task/toolNames/mode/writeScopes", required = false)
            List<FlowNodeInput> nodes,
            ToolContext toolContext) {
        TurnObservationContext observation = observation(toolContext);
        ItemEmitter emitter = emitter(toolContext);
        SandboxMode sandboxMode = sandboxMode(toolContext);
        String cwd = cwd(toolContext);
        BabiqFlowSpec spec = approvalService.approveOnce(toSpec(task, topology, nodes, sandboxMode), sandboxMode);
        if (cwd != null) {
            approvalService.validateWriteScopes(spec, Path.of(cwd));
        }
        repository.save(record(spec, observation, cwd), nodeRecords(spec, "pending", 0, 0, null));
        emit(emitter, item(spec, "running", "流程已审批并开始执行", spec.nodes()));
        try {
            Agent agent = orchestrationService.buildOfficialFlowAgent(spec, toolContext, null);
            Optional<OverAllState> state = invoke(agent, task, toolContext);
            String output = state
                    .map(value -> value.value(spec.mergeOutputKey(), value.toString()))
                    .map(Object::toString)
                    .orElse("流程已完成，但没有返回显式输出。");
            repository.save(record(spec, observation, cwd, "completed", output, null),
                    nodeRecords(spec, "completed", 0, 0, "节点已完成"));
            emit(emitter, item(spec, "completed", output, spec.nodes()));
            return output;
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            repository.save(record(spec, observation, cwd, "failed", null, message),
                    nodeRecords(spec, "failed", 0, 0, message));
            emit(emitter, item(spec, "failed", message, spec.nodes()));
            return "Flow failed: " + message;
        }
    }

    /**
     * 模型传入的流程节点参数。
     */
    public record FlowNodeInput(
            @ToolParam(description = "节点 ASCII 技术名，例如 scan 或 write") String name,
            @ToolParam(description = "节点展示名", required = false) String displayName,
            @ToolParam(description = "节点角色，例如 explorer、writer、reviewer", required = false) String role,
            @ToolParam(description = "节点任务") String task,
            @ToolParam(description = "节点可见工具白名单") List<String> toolNames,
            @ToolParam(description = "节点模式：READ_ONLY_TOOL 或 WORKSPACE_TOOL", required = false) String mode,
            @ToolParam(description = "可选 provider id；为空继承父 Agent", required = false) String providerId,
            @ToolParam(description = "写入范围，WORKSPACE_TOOL 节点必须尽量声明", required = false) List<String> writeScopes
    ) {
    }

    private Optional<OverAllState> invoke(Agent agent, String task, ToolContext toolContext) throws Exception {
        Object candidate = toolContext == null ? null : toolContext.getContext().get(SubAgentRuntimeFactory.AGENT_CONFIG_KEY);
        if (candidate instanceof RunnableConfig config) {
            return agent.invoke(task, config);
        }
        return agent.invoke(task);
    }

    private BabiqFlowSpec toSpec(String task, String topology, List<FlowNodeInput> nodes, SandboxMode sandboxMode) {
        String id = "orch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        List<BabiqFlowNode> flowNodes = nodes == null || nodes.isEmpty()
                ? List.of(defaultNode(task))
                : IntStream.range(0, nodes.size()).mapToObj(index -> toNode(nodes.get(index), index + 1)).toList();
        BabiqFlowTopology parsedTopology = switch (blankToDefault(topology, "sequential").toLowerCase(Locale.ROOT)) {
            case "parallel" -> BabiqFlowTopology.PARALLEL;
            case "routing", "route" -> BabiqFlowTopology.ROUTING;
            default -> BabiqFlowTopology.SEQUENTIAL;
        };
        return new BabiqFlowSpec(id, blankToDefault(task, "多 Agent 流程"), parsedTopology,
                flowNodes, "final", false, false, sandboxMode);
    }

    private BabiqFlowNode defaultNode(String task) {
        return new BabiqFlowNode(
                "node_explorer",
                "explorer",
                "探索节点",
                "explorer",
                blankToDefault(task, "查看当前上下文"),
                List.of("read_file", "list_dir", "grep"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                BabiqAgentMode.READ_ONLY_TOOL,
                1,
                null,
                "final",
                List.of());
    }

    private BabiqFlowNode toNode(FlowNodeInput input, int order) {
        String name = blankToDefault(input == null ? null : input.name(), "node_" + order);
        BabiqAgentMode mode = parseMode(input == null ? null : input.mode());
        return new BabiqFlowNode(
                "node_" + name,
                name,
                blankToDefault(input == null ? null : input.displayName(), name),
                blankToDefault(input == null ? null : input.role(), name),
                blankToDefault(input == null ? null : input.task(), ""),
                input == null || input.toolNames() == null ? List.of() : input.toolNames(),
                input == null || input.providerId() == null || input.providerId().isBlank()
                        ? BabiqAgentSpec.ModelPolicy.inherit()
                        : BabiqAgentSpec.ModelPolicy.provider(input.providerId()),
                mode,
                order,
                null,
                name + "_output",
                input == null || input.writeScopes() == null ? List.of() : input.writeScopes());
    }

    private OrchestrationRecord record(BabiqFlowSpec spec, TurnObservationContext observation, String cwd) {
        return record(spec, observation, cwd, "running", "流程运行中", null);
    }

    private OrchestrationRecord record(BabiqFlowSpec spec, TurnObservationContext observation, String cwd,
                                       String status, String summary, String error) {
        return new OrchestrationRecord(
                spec.orchestrationId(),
                observation == null ? null : observation.threadId(),
                observation == null ? null : observation.turnId(),
                spec.title(),
                spec.topology().name().toLowerCase(Locale.ROOT),
                status,
                cwd,
                spec.sandboxMode().name(),
                spec.approved(),
                spec.frozen(),
                summary,
                error);
    }

    private List<OrchestrationNodeRecord> nodeRecords(BabiqFlowSpec spec, String status,
                                                      int toolCalls, int tokens, String summary) {
        return spec.nodes().stream()
                .map(node -> new OrchestrationNodeRecord(
                        spec.orchestrationId(),
                        node.nodeId(),
                        node.name(),
                        node.displayName(),
                        node.mode().name(),
                        String.join(",", node.toolNames()),
                        status,
                        node.order(),
                        toolCalls,
                        tokens,
                        summary))
                .toList();
    }

    private OrchestrationItem item(BabiqFlowSpec spec, String status, String summary, List<BabiqFlowNode> nodes) {
        return new OrchestrationItem(
                "it_" + spec.orchestrationId(),
                TYPE,
                spec.orchestrationId(),
                spec.title(),
                spec.topology().name().toLowerCase(Locale.ROOT),
                status,
                summary,
                spec.approved(),
                spec.frozen(),
                nodes.stream()
                        .map(node -> new OrchestrationItem.NodeStatus(
                                node.nodeId(),
                                node.name(),
                                node.displayName(),
                                status,
                                node.mode().name(),
                                node.task(),
                                node.modelPolicy().providerId(),
                                0,
                                0,
                                summary))
                        .toList());
    }

    private void emit(ItemEmitter emitter, OrchestrationItem item) {
        if (emitter == null) {
            return;
        }
        try {
            if ("running".equals(item.status())) {
                emitter.emitItemAdded(item);
            } else {
                emitter.emitItemUpdated(item);
            }
        } catch (IOException ignored) {
            // UI 事件失败不能反向中断已经完成的流程；持久化记录仍然保留审计事实。
        }
    }

    private BabiqAgentMode parseMode(String mode) {
        if ("WORKSPACE_TOOL".equalsIgnoreCase(mode)) {
            return BabiqAgentMode.WORKSPACE_TOOL;
        }
        return BabiqAgentMode.READ_ONLY_TOOL;
    }

    private TurnObservationContext observation(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(TurnObservationContext.METADATA_KEY);
        return value instanceof TurnObservationContext observation ? observation : null;
    }

    private ItemEmitter emitter(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER);
        return value instanceof ItemEmitter emitter ? emitter : null;
    }

    private SandboxMode sandboxMode(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE);
        if (value instanceof String text) {
            try {
                return SandboxMode.valueOf(text);
            } catch (IllegalArgumentException ignored) {
                return SandboxMode.READ_ONLY;
            }
        }
        return SandboxMode.READ_ONLY;
    }

    private String cwd(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(BaBiQSandboxInterceptor.CONTEXT_CWD);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
