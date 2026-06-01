package com.wzx.babiq.server.agent.flow;

import java.util.List;

/**
 * 运行前整体审批弹窗需要展示的流程权限范围。
 *
 * <p>BaBiQ 不在并行分支里做运行中逐工具 HITL，P6-2 的用户确认必须在流程启动前完成。
 * 因此这里把节点、工具、写入范围和沙箱模式整理成一段可读说明，同时保留结构化列表供 UI 后续升级。</p>
 *
 * @param requiresApproval 是否包含写类节点或外部工具，需要运行前确认
 * @param description 审批弹窗可展示的摘要
 * @param nodeNames 参与流程的节点顺序
 * @param toolNames 流程声明会使用的工具集合
 * @param writeScopes 流程声明的写入范围
 */
public record FlowApprovalScope(
        boolean requiresApproval,
        String description,
        List<String> nodeNames,
        List<String> toolNames,
        List<String> writeScopes
) {
}
