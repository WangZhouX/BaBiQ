package com.wzx.babiq.server.agent.team;

/**
 * supervisor 的结构化路由决策。
 *
 * <p>next 只能是团队成员名或 FINISH。模型输出会先进入该结构，再由团队服务做白名单归一化；
 * 如果模型输出未知成员，服务会回退到 FINISH，避免 Graph 跳到未审批节点。</p>
 *
 * @param next 下一个节点：成员名或 FINISH
 * @param reason 路由原因，供右侧时间线展示
 * @param confidence 0-1 置信度，仅用于调试展示
 */
public record SupervisorRouteDecision(
        String next,
        String reason,
        double confidence
) {

    /**
     * 创建结束决策。
     */
    public static SupervisorRouteDecision finish(String reason) {
        return new SupervisorRouteDecision("FINISH", reason == null ? "团队目标已完成" : reason, 1.0d);
    }
}
