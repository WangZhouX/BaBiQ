package com.wzx.babiq.server.business.agent;

import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import com.wzx.babiq.server.business.workbench.BusinessScheduleService;
import com.wzx.babiq.server.business.workbench.BusinessWorkbenchService;
import com.wzx.babiq.server.tool.Tool;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read-only Agent access to the already secured business workbench BFF. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessWorkbenchReadTool implements Tool {
    private final BusinessWorkbenchService workbench;
    private final BusinessScheduleService schedules;
    private final BusinessAgentToolSupport support;

    BusinessWorkbenchReadTool(BusinessWorkbenchService workbench,
                              BusinessScheduleService schedules,
                              BusinessAgentToolSupport support) {
        this.workbench = Objects.requireNonNull(workbench, "workbench");
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public String name() {
        return "business_workbench_read";
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "business_workbench_read",
            description = """
                    Reads the authenticated OA workbench through the local BFF.
                    支持 snapshot、navigation、home_info、page、team_roles、schedule_month、
                    schedule_day、schedule_form、relation_options、service_projects。
                    This tool is read-only. Never pass credentials, OA URLs, raw methods or raw OA payloads.
                    """)
    public String read(
            @ToolParam(description = "强类型工作台查询参数；view 决定需要填写的其他字段") ReadRequest request,
            ToolContext toolContext) {
        return support.execute(toolContext, invocation -> dispatch(request, invocation));
    }

    private Object dispatch(ReadRequest request, BusinessAgentToolSupport.Invocation invocation) {
        if (request == null || blank(request.view())) {
            throw new IllegalArgumentException("view is required");
        }
        String view = request.view().trim().toLowerCase(java.util.Locale.ROOT);
        var lease = invocation.lease();
        var identity = invocation.identity();
        return switch (view) {
            case "snapshot" -> new BusinessWorkbenchDtos.SnapshotEnvelope(
                    identity.identityEpoch(), lease.generation(),
                    workbench.snapshot(lease, identity, text(request.month()), text(request.day())));
            case "navigation" -> workbench.navigation(lease, identity);
            case "home_info" -> workbench.homeInfo(lease, identity);
            case "page" -> new BusinessWorkbenchDtos.PageEnvelope(
                    identity.identityEpoch(), lease.generation(),
                    workbench.page(lease, identity, pageRequest(request)));
            case "team_roles" -> workbench.teamRoles(
                    lease, identity, required(request.kind()), required(request.teamId()));
            case "schedule_month" -> schedules.month(lease, identity, scheduleQuery(request, false));
            case "schedule_day" -> schedules.day(lease, identity, scheduleQuery(request, true));
            case "schedule_form" -> schedules.form(
                    lease, identity, required(request.scope()), text(request.teamId()));
            case "relation_options" -> schedules.relationOptions(
                    lease, identity, required(request.relationType()), text(request.keyword()),
                    text(request.teamId()), text(request.parentId()));
            case "service_projects" -> schedules.serviceProjects(
                    lease, identity, required(request.recordId()), text(request.keyword()), text(request.teamId()));
            default -> throw new IllegalArgumentException("unsupported view");
        };
    }

    private static BusinessWorkbenchDtos.PageRequest pageRequest(ReadRequest request) {
        String kind = required(request.kind()).toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> filters = request.filterValue() == null
                ? Map.of()
                : Map.of(switch (kind) {
                    case "CASE" -> "status";
                    case "APPOINTMENT" -> "consultMode";
                    case "COUNSELOR_SERVICE" -> "serviceStatus";
                    case "VISIT" -> "visitObj";
                    default -> throw new IllegalArgumentException("invalid kind");
                }, request.filterValue());
        return new BusinessWorkbenchDtos.PageRequest(
                kind,
                required(request.scope()).toUpperCase(java.util.Locale.ROOT),
                text(request.teamId()),
                text(request.roleCode()),
                request.pageNo() == null ? 1 : request.pageNo(),
                request.pageSize() == null ? 20 : request.pageSize(),
                filters);
    }

    private static BusinessWorkbenchDtos.ScheduleQuery scheduleQuery(ReadRequest request, boolean day) {
        return new BusinessWorkbenchDtos.ScheduleQuery(
                required(request.date()),
                required(request.scope()).toUpperCase(java.util.Locale.ROOT),
                text(request.teamId()),
                Boolean.TRUE.equals(request.onlyMine()),
                day ? text(request.typeId()) : null);
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

    public record ReadRequest(
            @ToolParam(description = "查询视图") String view,
            @ToolParam(description = "snapshot 的月份 yyyy-MM", required = false) String month,
            @ToolParam(description = "snapshot 的日期 yyyy-MM-dd", required = false) String day,
            @ToolParam(description = "日程查询日期，month 用 yyyy-MM，day 用 yyyy-MM-dd", required = false) String date,
            @ToolParam(description = "ALL、PERSONAL 或 TEAM", required = false) String scope,
            @ToolParam(description = "TEAM 范围的团队 ID", required = false) String teamId,
            @ToolParam(description = "是否只查本人日程", required = false) Boolean onlyMine,
            @ToolParam(description = "日程类型 ID", required = false) String typeId,
            @ToolParam(description = "CASE、APPOINTMENT、COUNSELOR_SERVICE 或 VISIT", required = false) String kind,
            @ToolParam(description = "TEAM 页的数据角色编码", required = false) String roleCode,
            @ToolParam(description = "页码", required = false) Integer pageNo,
            @ToolParam(description = "每页数量", required = false) Integer pageSize,
            @ToolParam(description = "当前 kind 的唯一数字筛选值", required = false) Integer filterValue,
            @ToolParam(description = "CASE、CUSTOMER、VISIT 或 SERVICE", required = false) String relationType,
            @ToolParam(description = "关系选项关键词", required = false) String keyword,
            @ToolParam(description = "服务项目父记录 ID", required = false) String parentId,
            @ToolParam(description = "服务记录 ID", required = false) String recordId
    ) {
    }
}
