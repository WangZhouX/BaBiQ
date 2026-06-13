package com.wzx.babiq.server.agent.flow;

import com.wzx.babiq.server.sandbox.SandboxMode;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 一次多 Agent 流程编排的冻结规格。
 *
 * <p>该对象是 approve-once 的核心：用户审批通过后，拓扑、节点、工具白名单、
 * 写入范围、沙箱模式和 P8 画布结构都被固定下来。后续执行只读取这份规格，
 * 不允许运行中新增节点、提升权限或扩大写入范围。</p>
 *
 * @param orchestrationId 流程 id，贯穿协议 item、运行记录和持久化表
 * @param title 用户可读标题
 * @param topology 根拓扑；旧版平铺流程会被升级成同拓扑的根组
 * @param nodes 节点列表，构造时按 order 排序并去重校验
 * @param mergeOutputKey 旧版根并行流程的 Spring AI Alibaba merge 输出键
 * @param approved 是否已经获得运行前整体审批
 * @param frozen 是否冻结结构；冻结后不能替换节点列表
 * @param sandboxMode 本轮沙箱快照，不允许流程自行提升
 * @param structure P8 画布结构树；为空时按旧版平铺拓扑自动升级
 */
public record BabiqFlowSpec(
        String orchestrationId,
        String title,
        BabiqFlowTopology topology,
        List<BabiqFlowNode> nodes,
        String mergeOutputKey,
        boolean approved,
        boolean frozen,
        SandboxMode sandboxMode,
        BabiqFlowStructure structure
) {

    public BabiqFlowSpec(String orchestrationId,
                         String title,
                         BabiqFlowTopology topology,
                         List<BabiqFlowNode> nodes,
                         String mergeOutputKey,
                         boolean approved,
                         boolean frozen,
                         SandboxMode sandboxMode) {
        this(orchestrationId, title, topology, nodes, mergeOutputKey, approved, frozen, sandboxMode, null);
    }

    /**
     * 规格化流程规格，确保后续装配官方 FlowAgent 时拿到稳定顺序和不可变列表。
     */
    public BabiqFlowSpec {
        if (orchestrationId == null || orchestrationId.isBlank()) {
            throw new IllegalArgumentException("流程 id 不能为空");
        }
        title = title == null || title.isBlank() ? orchestrationId : title;
        topology = topology == null ? BabiqFlowTopology.SEQUENTIAL : topology;
        nodes = nodes == null ? List.of() : nodes.stream()
                .sorted(Comparator.comparingInt(BabiqFlowNode::order))
                .toList();
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("流程至少需要一个节点");
        }
        assertUnique(nodes);
        structure = structure == null ? BabiqFlowStructure.fromLegacy(topology, nodes) : structure;
        structure.validateAgainst(nodes);
        topology = structure.root().topology();
        mergeOutputKey = mergeOutputKey == null || mergeOutputKey.isBlank() ? "flow_output" : mergeOutputKey;
        sandboxMode = sandboxMode == null ? SandboxMode.READ_ONLY : sandboxMode;
    }

    /**
     * 判断整个流程是否包含写类节点。
     */
    public boolean requiresWriteAccess() {
        return nodes.stream().anyMatch(BabiqFlowNode::requiresWriteAccess);
    }

    /**
     * 按节点技术名或 id 查找节点，供运行期更新状态。
     */
    public Optional<BabiqFlowNode> node(String nameOrId) {
        return nodes.stream()
                .filter(node -> node.name().equals(nameOrId) || node.nodeId().equals(nameOrId))
                .findFirst();
    }

    /**
     * 返回替换节点后的新规格；已冻结流程禁止修改。
     */
    public BabiqFlowSpec withNodes(List<BabiqFlowNode> replacement) {
        if (frozen) {
            throw new IllegalStateException("流程已冻结，不能修改节点");
        }
        return new BabiqFlowSpec(orchestrationId, title, topology, replacement, mergeOutputKey, approved, false, sandboxMode, null);
    }

    /**
     * 返回审批通过且冻结后的规格，沙箱模式来自 turn 快照而不是节点自行决定。
     */
    public BabiqFlowSpec approvedAndFrozen(SandboxMode effectiveSandboxMode) {
        return new BabiqFlowSpec(orchestrationId, title, topology, nodes, mergeOutputKey, true, true, effectiveSandboxMode, structure);
    }

    private static void assertUnique(List<BabiqFlowNode> nodes) {
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (BabiqFlowNode node : nodes) {
            if (!ids.add(node.nodeId()) || !names.add(node.name())) {
                throw new IllegalArgumentException("流程节点 id/name 不能重复");
            }
        }
    }
}
