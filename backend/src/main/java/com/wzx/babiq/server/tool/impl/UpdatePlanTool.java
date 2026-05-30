package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.PlanItem;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.tool.Tool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Agent 计划更新工具。
 *
 * <p>它对齐 Codex `update_plan` 与 Claude Code `TodoWrite` 的核心语义：模型每次传入完整计划，
 * 后端把最新计划作为 `plan` item 发给桌面端。工具本身不写文件、不执行命令，因此不走审批和沙箱写保护。</p>
 */
@Component
public class UpdatePlanTool implements Tool {

    private static final String TYPE = "plan";
    private static final String UPDATED = "Plan updated";

    /**
     * 返回协议层工具名，必须保持 ASCII，中文检索词只进入 capability searchText。
     */
    @Override
    public String name() {
        return "update_plan";
    }

    /**
     * 更新当前 turn 的可视化计划。
     *
     * @param goal 可选计划目标；为空时桌面端只展示步骤清单
     * @param explanation 可选更新说明；会写入 PlanItem.reasoning 供运行详情查看
     * @param plan 完整计划步骤；每次调用都必须重发完整列表
     * @param toolContext Spring AI 工具上下文，携带 ItemEmitter 和 TurnObservationContext
     * @return 给模型的极短确认文本；完整计划已经通过 item 事件展示给用户
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "update_plan",
            description = """
                    Updates the visible task plan for complex multi-step work. 更新右侧进度面板中的计划、任务清单、待办、步骤。
                    Always pass the full plan list. Valid status values are pending, in_progress, completed.
                    At most one step can be in_progress at a time. Do not use for simple one-step or purely informational requests.
                    """)
    public String updatePlan(
            @ToolParam(description = "可选计划目标；没有单独目标时留空", required = false) String goal,
            @ToolParam(description = "可选计划更新说明，简短解释本次调整", required = false) String explanation,
            @ToolParam(description = "完整计划步骤列表，每步包含 description/status/可选 activeForm") List<PlanStepInput> plan,
            ToolContext toolContext) {
        ItemEmitter emitter = emitter(toolContext);
        TurnObservationContext observation = observation(toolContext);
        if (emitter == null || observation == null) {
            return "Plan update failed: missing BaBiQ turn context";
        }
        if (plan == null) {
            return "Plan update failed: plan is required";
        }

        String existingId = observation.planItemId();
        String itemId = existingId == null ? observation.rememberPlanItemId(newItemId()) : existingId;
        PlanItem item = new PlanItem(itemId, TYPE, blankToNull(goal), steps(plan), blankToNull(explanation));
        try {
            if (existingId == null) {
                emitter.emitItemAdded(item);
            } else {
                emitter.emitItemUpdated(item);
            }
            return UPDATED;
        } catch (IOException exception) {
            return "Plan update failed: " + exception.getMessage();
        }
    }

    /**
     * 模型传入的单个计划步骤。
     *
     * @param description 步骤描述，使用祈使句，例如“运行测试”
     * @param status 步骤状态，必须是 pending / in_progress / completed
     * @param activeForm 可选进行时文案，例如“正在运行测试”
     */
    public record PlanStepInput(
            @ToolParam(description = "步骤描述，使用祈使句，例如“运行测试”") String description,
            @ToolParam(description = "步骤状态：pending、in_progress 或 completed") String status,
            @ToolParam(description = "可选进行时文案；进行中步骤优先展示它", required = false) String activeForm
    ) {
    }

    private List<PlanItem.PlanStep> steps(List<PlanStepInput> plan) {
        List<PlanItem.PlanStep> steps = new ArrayList<>();
        for (int index = 0; index < plan.size(); index++) {
            PlanStepInput input = plan.get(index);
            steps.add(new PlanItem.PlanStep(
                    index + 1,
                    blankToDefault(input == null ? null : input.description(), "未命名步骤"),
                    normalizeStatus(input == null ? null : input.status()),
                    blankToNull(input == null ? null : input.activeForm())));
        }
        return List.copyOf(steps);
    }

    private String normalizeStatus(String status) {
        String normalized = blankToDefault(status, "pending").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "pending", "in_progress", "completed" -> normalized;
            default -> "pending";
        };
    }

    private ItemEmitter emitter(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER);
        return value instanceof ItemEmitter emitter ? emitter : null;
    }

    private TurnObservationContext observation(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(TurnObservationContext.METADATA_KEY);
        return value instanceof TurnObservationContext observation ? observation : null;
    }

    private String newItemId() {
        return "it_plan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
