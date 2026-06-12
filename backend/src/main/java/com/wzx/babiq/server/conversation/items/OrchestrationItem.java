package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * P6-2 流程编排过程的协议 item。
 *
 * <p>聊天流只展示父 Agent 的结论；流程内部节点、状态、工具次数和简短摘要通过该 item
 * 汇总到右侧运行详情，避免把子 Agent 中间消息污染主对话历史。</p>
 *
 * @param id 协议 item id，同一次流程的 added/updated 使用同一个 id
 * @param type 固定为 orchestration
 * @param orchestrationId 流程运行 id
 * @param title 用户可读标题
 * @param topology sequential、parallel 或 routing
 * @param status running、completed 或 failed
 * @param summary 流程整体摘要
 * @param approved 是否已通过运行前整体审批
 * @param frozen 是否已冻结拓扑和节点
 * @param nodes 节点状态列表，按运行视图排序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrchestrationItem(
        String id,
        String type,
        String orchestrationId,
        String title,
        String topology,
        String status,
        String summary,
        Boolean approved,
        Boolean frozen,
        String structureJson,
        List<NodeStatus> nodes
) implements ThreadItem {
    public OrchestrationItem(String id,
                             String type,
                             String orchestrationId,
                             String title,
                             String topology,
                             String status,
                             String summary,
                             Boolean approved,
                             Boolean frozen,
                             List<NodeStatus> nodes) {
        this(id, type, orchestrationId, title, topology, status, summary, approved, frozen, null, nodes);
    }

    /**
     * 单个流程节点的 UI 状态。
     *
     * @param nodeId 节点 id
     * @param name 节点技术名
     * @param displayName 展示名
     * @param status pending、running、completed 或 failed
     * @param mode READ_ONLY_TOOL 或 WORKSPACE_TOOL
     * @param task 节点任务
     * @param model 节点模型名；为空表示继承父 Agent
     * @param toolCallCount 节点聚合工具次数
     * @param tokenEstimate 节点 token 粗估
     * @param summary 节点短摘要
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeStatus(
            String nodeId,
            String name,
            String displayName,
            String status,
            String mode,
            String task,
            String model,
            Integer toolCallCount,
            Integer tokenEstimate,
            String summary
    ) {
    }
}
