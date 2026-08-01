package com.wzx.babiq.server.business.agent;

import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import com.wzx.babiq.server.business.workbench.BusinessScheduleService;
import com.wzx.babiq.server.tool.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Approved and audited schedule mutations through the existing server-owned BFF. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessScheduleMutationTool implements Tool {
    private final BusinessScheduleService schedules;
    private final BusinessAgentToolSupport support;

    BusinessScheduleMutationTool(BusinessScheduleService schedules, BusinessAgentToolSupport support) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public String name() {
        return "business_schedule_mutate";
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "business_schedule_mutate",
            description = """
                    Mutates the authenticated OA workbench schedule through the local BFF.
                    支持 set_completion、update_sort、create。调用前必须先用 business_workbench_read
                    获取当前日程、排序 revision 或表单 revision/选项；不得传任意 OA 方法、凭据或原始 payload。
                    This is a write tool and is subject to the current approval and sandbox policy.
                    """)
    public String mutate(
            @ToolParam(description = "强类型日程写请求；operation 决定需要填写的其他字段") MutationRequest request,
            ToolContext toolContext) {
        return support.execute(toolContext, invocation -> dispatch(request, invocation));
    }

    private Object dispatch(MutationRequest request, BusinessAgentToolSupport.Invocation invocation) {
        if (request == null || blank(request.operation())) {
            throw new IllegalArgumentException("operation is required");
        }
        var lease = invocation.lease();
        var identity = invocation.identity();
        return switch (request.operation().trim().toLowerCase(java.util.Locale.ROOT)) {
            case "set_completion" -> schedules.setCompletion(
                    lease, identity, required(request.scheduleId()), required(request.completed()));
            case "update_sort" -> schedules.updateSort(
                    lease, identity, required(request.sortKind()), requiredList(request.ids()),
                    required(request.expectedRevision()));
            case "create" -> schedules.create(lease, identity, createRequest(request.create()));
            default -> throw new IllegalArgumentException("unsupported operation");
        };
    }

    private static BusinessWorkbenchDtos.ScheduleCreateRequest createRequest(ScheduleCreateInput input) {
        if (input == null) {
            throw new IllegalArgumentException("create request is required");
        }
        List<Map<String, Object>> relations = new ArrayList<>();
        for (RelationInput relation : input.relations() == null ? List.<RelationInput>of() : input.relations()) {
            if (relation == null) {
                throw new IllegalArgumentException("invalid relation");
            }
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("relationType", required(relation.relationType()));
            mapped.put("relationId", required(relation.relationId()));
            put(mapped, "relationTitle", relation.relationTitle());
            put(mapped, "parentId", relation.parentId());
            relations.add(Map.copyOf(mapped));
        }
        return new BusinessWorkbenchDtos.ScheduleCreateRequest(
                required(input.clientOperationId()),
                required(input.scope()).toUpperCase(java.util.Locale.ROOT),
                text(input.teamId()),
                text(input.assigneeUserId()),
                required(input.title()),
                required(input.typeId()),
                required(input.at()),
                input.allDay(),
                input.priority(),
                text(input.description()),
                input.reminderMinutes() == null ? List.of() : List.copyOf(input.reminderMinutes()),
                List.copyOf(relations),
                null,
                null,
                null,
                input.formRevision(),
                input.repetition());
    }

    private static void put(Map<String, Object> target, String key, String value) {
        String normalized = text(value);
        if (normalized != null) {
            target.put(key, normalized);
        }
    }

    private static boolean required(Boolean value) {
        if (value == null) {
            throw new IllegalArgumentException("boolean is required");
        }
        return value;
    }

    private static long required(Long value) {
        if (value == null) {
            throw new IllegalArgumentException("revision is required");
        }
        return value;
    }

    private static List<String> requiredList(List<String> values) {
        if (values == null) {
            throw new IllegalArgumentException("ids are required");
        }
        return List.copyOf(values);
    }

    private static String required(String value) {
        String normalized = text(value);
        if (normalized == null) {
            throw new IllegalArgumentException("required field is missing");
        }
        return normalized;
    }

    private static String text(String value) {
        return blank(value) ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record MutationRequest(
            @ToolParam(description = "set_completion、update_sort 或 create") String operation,
            @ToolParam(description = "完成状态操作的日程 ID", required = false) String scheduleId,
            @ToolParam(description = "目标完成状态", required = false) Boolean completed,
            @ToolParam(description = "SHORTCUT 或 SUMMARY", required = false) String sortKind,
            @ToolParam(description = "完整排序 ID 列表", required = false) List<String> ids,
            @ToolParam(description = "当前排序 revision", required = false) Long expectedRevision,
            @ToolParam(description = "新建日程参数", required = false) ScheduleCreateInput create
    ) {
    }

    public record ScheduleCreateInput(
            @ToolParam(description = "本次意图稳定且唯一的幂等 ID") String clientOperationId,
            @ToolParam(description = "PERSONAL 或 TEAM") String scope,
            @ToolParam(description = "TEAM 的团队 ID", required = false) String teamId,
            @ToolParam(description = "TEAM 的受派用户 ID", required = false) String assigneeUserId,
            @ToolParam(description = "日程标题，最多 50 字") String title,
            @ToolParam(description = "从 schedule_form 获取的类型 ID") String typeId,
            @ToolParam(description = "yyyy-MM-dd HH:mm:ss") String at,
            @ToolParam(description = "是否全天") boolean allDay,
            @ToolParam(description = "优先级 1 到 4") int priority,
            @ToolParam(description = "可选描述，最多 200 字", required = false) String description,
            @ToolParam(description = "提醒分钟列表", required = false) List<Integer> reminderMinutes,
            @ToolParam(description = "已由 relation_options 授权的关系", required = false) List<RelationInput> relations,
            @ToolParam(description = "从 schedule_form 获取的 revision") long formRevision,
            @ToolParam(description = "重复类型 0 到 4") int repetition
    ) {
    }

    public record RelationInput(
            @ToolParam(description = "CASE、CUSTOMER、VISIT 或 SERVICE") String relationType,
            @ToolParam(description = "关系记录 ID") String relationId,
            @ToolParam(description = "关系显示标题", required = false) String relationTitle,
            @ToolParam(description = "SERVICE 的父记录 ID", required = false) String parentId
    ) {
    }
}
