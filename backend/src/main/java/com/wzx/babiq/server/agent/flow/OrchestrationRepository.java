package com.wzx.babiq.server.agent.flow;

import java.util.List;
import java.util.Optional;

/**
 * 流程编排运行记录仓储端口。
 *
 * <p>Agent/工具层通过该端口写入运行状态，SQLite 只是当前实现；
 * 后续如果把运行详情拆到事件表，也不会影响编排服务。</p>
 */
public interface OrchestrationRepository {

    /**
     * 保存一次流程运行和它的初始节点列表。
     */
    void save(OrchestrationRecord record, List<OrchestrationNodeRecord> nodes);

    /**
     * 更新单个节点聚合状态。
     */
    void updateNode(String orchestrationId, String nodeId, String status, int toolCallCount, int tokenEstimate, String summary);

    /**
     * 按流程 id 查询运行记录。
     */
    Optional<OrchestrationRecord> findByOrchestrationId(String orchestrationId);

    /**
     * 查询流程节点列表。
     */
    List<OrchestrationNodeRecord> listNodes(String orchestrationId);
}
