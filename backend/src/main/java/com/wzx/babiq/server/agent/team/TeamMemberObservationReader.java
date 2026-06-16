package com.wzx.babiq.server.agent.team;

/**
 * 团队成员观测读取端口。
 *
 * <p>生产实现从 `bq_tool_calls` 读取成员归属，测试可替换为固定值，以便只验证
 * 团队协调服务的 capture 语义。</p>
 */
public interface TeamMemberObservationReader {

    /**
     * 读取某个成员在本轮执行后的聚合观测。
     *
     * @param turnId 当前主 turn id；为空时无法聚合工具调用
     * @param memberName 成员技术名
     * @param fullText 成员完整输出，用于 token 粗估
     * @return 成员观测
     */
    TeamMemberObservation read(String turnId, String memberName, String fullText);
}
