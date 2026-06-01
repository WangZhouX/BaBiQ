package com.wzx.babiq.server.agent.flow;

/**
 * BaBiQ P6-2 支持的流程拓扑。
 *
 * <p>枚举只暴露 Spring AI Alibaba Agent Framework 已经提供的稳定流程形态：
 * 顺序、并行和 LLM 路由。LoopAgent、任意有向图和运行中 HITL 恢复留给后续阶段，
 * 避免在 P6-2 重复造编排引擎。</p>
 */
public enum BabiqFlowTopology {

    /** 顺序执行节点，适合“先探索、再修改、最后复核”的线性任务。 */
    SEQUENTIAL,

    /** 并行执行节点，适合多个只读探索或多个互不相干的工作分支。 */
    PARALLEL,

    /** 由 LLM 在候选子 Agent 中选择路由目标，适合意图分流。 */
    ROUTING
}
