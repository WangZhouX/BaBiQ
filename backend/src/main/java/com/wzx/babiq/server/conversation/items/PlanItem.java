package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Agent 计划 item。
 *
 * <p>计划 item 用于表达 Agent 准备执行的多步工作。P1-1 先固化 wire schema,
 * 后续前端可直接基于 steps 渲染任务进度。</p>
 *
 * @param id item 标识
 * @param type 固定为 plan
 * @param goal 计划目标
 * @param steps 计划步骤
 * @param reasoning 生成计划时的简短依据
 */
public record PlanItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,
        @JsonProperty(required = true) String goal,
        @JsonProperty(required = true) List<PlanStep> steps,
        @JsonProperty(required = true) String reasoning
) implements ThreadItem {

    /**
     * 计划中的单个步骤。
     *
     * @param order 步骤顺序,从 1 开始
     * @param description 步骤描述
     */
    public record PlanStep(
            @JsonProperty(required = true) int order,
            @JsonProperty(required = true) String description
    ) {
    }
}
