package com.wzx.babiq.server.agent.flow;

import com.alibaba.cloud.ai.graph.agent.Agent;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 把 BaBiQ 流程节点转换成 Spring AI Alibaba Agent 的工厂接口。
 *
 * <p>生产环境由 {@code SubAgentRuntimeFactory} 提供真实 ReactAgent；
 * 单元测试可以替换为轻量 mock，避免触发外部模型调用。</p>
 */
@FunctionalInterface
public interface FlowNodeAgentFactory {

    /**
     * 为一个流程节点创建可被官方 FlowAgent 编排的子 Agent。
     *
     * @param node 节点规格
     * @param toolContext 已注入 cwd、沙箱、delegation 等运行上下文的工具上下文
     * @return Spring AI Alibaba Agent 实例
     */
    Agent create(BabiqFlowNode node, ToolContext toolContext);
}
