package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 团队协作整体状态协议 item。
 *
 * <p>它是 P6-3 右侧运行详情的主卡片：展示团队目标、当前成员、调度轮数和成员聚合状态。
 * 该 item 不进入模型上下文，也不作为普通聊天消息展示。</p>
 *
 * @param id 协议 item id，同一团队的 added/updated 使用同一个 id
 * @param type 固定为 team，供桌面端多态反序列化
 * @param teamId 团队运行 id
 * @param title 用户可读标题
 * @param status pending、running、completed 或 failed
 * @param summary 团队短摘要
 * @param approved 是否已经通过运行前整体审批
 * @param frozen 是否已冻结成员、工具和写入范围
 * @param currentAgent 当前或最近被调度成员
 * @param round 当前调度轮数
 * @param maxRounds 最多调度轮数
 * @param members 成员聚合状态列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamItem(
        String id,
        String type,
        String teamId,
        String title,
        String status,
        String summary,
        Boolean approved,
        Boolean frozen,
        String currentAgent,
        Integer round,
        Integer maxRounds,
        List<MemberStatus> members
) implements ThreadItem {

    /**
     * 团队成员聚合状态。
     *
     * @param memberId 协议层成员 id
     * @param name 成员 ASCII 技术名
     * @param displayName 桌面端展示名
     * @param status 成员状态
     * @param mode 成员委派模式
     * @param task 成员任务
     * @param toolCallCount 成员聚合工具调用次数
     * @param tokenEstimate 成员 token 粗估值
     * @param summary 成员短摘要
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemberStatus(
            String memberId,
            String name,
            String displayName,
            String status,
            String mode,
            String task,
            Integer toolCallCount,
            Integer tokenEstimate,
            String summary
    ) {
    }
}
