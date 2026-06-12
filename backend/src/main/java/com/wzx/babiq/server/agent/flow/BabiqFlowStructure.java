package com.wzx.babiq.server.agent.flow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 流程画布的受限嵌套结构树。
 *
 * <p>P8 不引入任意 DAG 执行引擎，而是把 P6-2 的平铺节点列表组织成一棵可审计的受限树：
 * 根是一个组，根组下可以放节点引用或一层子组，子组内部只能再放节点引用。这个形状刚好对应
 * Spring AI Alibaba 官方 SequentialAgent、ParallelAgent、LlmRoutingAgent 的递归组合能力。</p>
 *
 * @param root 根组，描述整个编排的拓扑和有序子项
 */
public record BabiqFlowStructure(FlowGroup root) {

    public static final String ROOT_GROUP_ID = "g_root";

    public BabiqFlowStructure {
        if (root == null) {
            throw new IllegalArgumentException("流程结构根组不能为空");
        }
    }

    /**
     * 把旧版平铺拓扑升级成单根组结构，供旧数据和旧协议继续按原语义运行。
     */
    public static BabiqFlowStructure fromLegacy(BabiqFlowTopology topology, List<BabiqFlowNode> nodes) {
        BabiqFlowTopology safeTopology = topology == null ? BabiqFlowTopology.SEQUENTIAL : topology;
        List<FlowEntry> children = (nodes == null ? List.<BabiqFlowNode>of() : nodes).stream()
                .sorted(Comparator.comparingInt(BabiqFlowNode::order))
                .map(node -> (FlowEntry) new FlowNodeRef(node.nodeId()))
                .toList();
        return new BabiqFlowStructure(new FlowGroup(ROOT_GROUP_ID, safeTopology, children));
    }

    /**
     * 按深度优先顺序展开节点引用，用于审批范围、旧版 UI 降级和运行记录回放。
     */
    public List<String> flattenNodeIds() {
        List<String> ids = new ArrayList<>();
        flatten(root, ids);
        return List.copyOf(ids);
    }

    /**
     * 校验结构树与平铺节点列表完全一致：每个节点必须存在，且恰好被引用一次。
     */
    public void validateAgainst(List<BabiqFlowNode> nodes) {
        List<BabiqFlowNode> safeNodes = nodes == null ? List.of() : nodes;
        Set<String> declaredNodeIds = new LinkedHashSet<>();
        for (BabiqFlowNode node : safeNodes) {
            if (!declaredNodeIds.add(node.nodeId())) {
                throw new IllegalArgumentException("流程节点 id 不能重复: " + node.nodeId());
            }
        }

        Set<String> referencedNodeIds = new LinkedHashSet<>();
        Set<String> groupIds = new HashSet<>();
        validateGroup(root, 1, declaredNodeIds, referencedNodeIds, groupIds);

        Set<String> missing = new LinkedHashSet<>(declaredNodeIds);
        missing.removeAll(referencedNodeIds);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("流程节点未被结构引用: " + missing);
        }
    }

    private static void validateGroup(FlowGroup group,
                                      int depth,
                                      Set<String> declaredNodeIds,
                                      Set<String> referencedNodeIds,
                                      Set<String> groupIds) {
        if (group.groupId() == null || group.groupId().isBlank()) {
            throw new IllegalArgumentException("流程组 id 不能为空");
        }
        if (!groupIds.add(group.groupId())) {
            throw new IllegalArgumentException("流程组 id 不能重复: " + group.groupId());
        }
        if (group.children().isEmpty()) {
            throw new IllegalArgumentException("流程组至少需要一个子项: " + group.groupId());
        }
        for (FlowEntry child : group.children()) {
            if (child instanceof FlowGroup childGroup && depth >= 2) {
                throw new IllegalArgumentException("组内不能再嵌套组: " + childGroup.groupId());
            }
        }
        if ((group.topology() == BabiqFlowTopology.PARALLEL || group.topology() == BabiqFlowTopology.ROUTING)
                && group.children().size() < 2) {
            throw new IllegalArgumentException(group.topology() + " 组至少两个子项: " + group.groupId());
        }

        for (FlowEntry child : group.children()) {
            if (child instanceof FlowNodeRef ref) {
                validateNodeRef(ref, declaredNodeIds, referencedNodeIds);
            } else if (child instanceof FlowGroup childGroup) {
                validateGroup(childGroup, depth + 1, declaredNodeIds, referencedNodeIds, groupIds);
            }
        }
    }

    private static void validateNodeRef(FlowNodeRef ref,
                                        Set<String> declaredNodeIds,
                                        Set<String> referencedNodeIds) {
        if (ref.nodeId() == null || ref.nodeId().isBlank()) {
            throw new IllegalArgumentException("流程节点引用不能为空");
        }
        if (!declaredNodeIds.contains(ref.nodeId())) {
            throw new IllegalArgumentException("流程结构引用了不存在的节点: " + ref.nodeId());
        }
        if (!referencedNodeIds.add(ref.nodeId())) {
            throw new IllegalArgumentException("流程结构重复引用节点: " + ref.nodeId());
        }
    }

    private static void flatten(FlowEntry entry, List<String> ids) {
        if (entry instanceof FlowNodeRef ref) {
            ids.add(ref.nodeId());
            return;
        }
        FlowGroup group = (FlowGroup) entry;
        group.children().forEach(child -> flatten(child, ids));
    }

    /**
     * 结构树条目。Jackson 通过字段集合推断是节点引用还是组，不在 JSON 中额外写类型字段。
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({
            @JsonSubTypes.Type(FlowGroup.class),
            @JsonSubTypes.Type(FlowNodeRef.class)
    })
    public sealed interface FlowEntry permits FlowGroup, FlowNodeRef {
    }

    /**
     * 拓扑组；子项保持有序，顺序就是画布和执行的共同语义。
     */
    public record FlowGroup(
            String groupId,
            BabiqFlowTopology topology,
            List<FlowEntry> children
    ) implements FlowEntry {

        public FlowGroup {
            topology = topology == null ? BabiqFlowTopology.SEQUENTIAL : topology;
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    /**
     * 指向 {@link BabiqFlowSpec#nodes()} 中节点的叶子引用。
     */
    public record FlowNodeRef(String nodeId) implements FlowEntry {
    }
}
