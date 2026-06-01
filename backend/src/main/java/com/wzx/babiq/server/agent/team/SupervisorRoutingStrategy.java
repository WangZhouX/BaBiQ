package com.wzx.babiq.server.agent.team;

import java.util.List;

/**
 * supervisor 路由策略端口。
 *
 * <p>生产实现优先使用 Spring AI structured output 让模型选择下一名成员；
 * 出错或输出不合法时，团队服务会使用确定性 fallback，防止协作图卡死。</p>
 */
public interface SupervisorRoutingStrategy {

    /**
     * 根据团队规格、已有时间线和当前轮数选择下一步。
     */
    SupervisorRouteDecision decide(BabiqTeamSpec spec, List<TeamMessageRecord> timeline, int round);
}
