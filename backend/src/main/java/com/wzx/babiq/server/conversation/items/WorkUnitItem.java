package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工作容器状态 item。
 *
 * <p>P6-4 用它把“已创建但尚未启动”的编排/团队容器推给桌面端。它不代表真实执行已经开始，
 * 真实执行仍由用户在详情页点击开始，或由主 Agent 在用户明确要求后调用相应启动工具。</p>
 *
 * @param id 协议 item id
 * @param type 固定为 workUnit
 * @param workUnitId 工作容器 id
 * @param kind orchestration 或 team
 * @param name 用户可读名称
 * @param status waiting_config、running、completed、failed 或 removed；待启动是 waiting_config 下的 UI 提示
 * @param activeGoalId 当前最近追加的目标 id
 * @param activeGoal 当前最近追加的目标文本
 * @param goalCount 容器内目标数量
 * @param linkedRunId 真实启动后关联的 orch_ 或 team_ 运行 id
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkUnitItem(
        String id,
        String type,
        String workUnitId,
        String kind,
        String name,
        String status,
        String activeGoalId,
        String activeGoal,
        Integer goalCount,
        String linkedRunId
) implements ThreadItem {
}
