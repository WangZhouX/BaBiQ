package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * team/get 返回结果。
 *
 * @param team 团队摘要
 * @param members 成员聚合状态
 * @param messages 团队时间线消息
 */
public record TeamGetResult(
        TeamInfo team,
        List<TeamMemberInfo> members,
        List<TeamMessageInfo> messages
) {
}
