package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * @param goal 可选计划目标；模型没有单独目标时为空，桌面端只展示步骤列表
 * @param steps 计划步骤；每次 update_plan 都会全量覆盖这份列表
 * @param reasoning 可选计划更新说明；用于运行详情审计，不要求模型每次都填写
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,
        @JsonProperty String goal,
        @JsonProperty(required = true) List<PlanStep> steps,
        @JsonProperty String reasoning
) implements ThreadItem {

    /**
     * 计划中的单个步骤。
     *
     * @param order 步骤顺序,从 1 开始
     * @param description 步骤描述，使用祈使句，例如“运行测试”
     * @param status 步骤状态，只允许 pending / in_progress / completed 三态
     * @param activeForm 可选进行时文案；当 status=in_progress 时桌面端优先显示它
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanStep(
            @JsonProperty(required = true) int order,
            @JsonProperty(required = true) String description,
            @JsonProperty(required = true) String status,
            @JsonProperty String activeForm
    ) {
    }
}
