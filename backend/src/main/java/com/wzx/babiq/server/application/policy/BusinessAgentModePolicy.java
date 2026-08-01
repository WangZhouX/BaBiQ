package com.wzx.babiq.server.application.policy;

import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/** 业务桌面模式的模型能力硬边界。 */
@Component
public final class BusinessAgentModePolicy {

    private static final List<String> BUSINESS_MODEL_TOOLS = List.of(
            "application_action",
            "business_workbench_read",
            "business_schedule_mutate",
            "update_plan");

    private final boolean businessMode;

    @Autowired
    public BusinessAgentModePolicy(BusinessDesktopModeProperties properties) {
        this(properties.enabled());
    }

    public BusinessAgentModePolicy(boolean businessMode) {
        this.businessMode = businessMode;
    }

    public boolean businessMode() {
        return businessMode;
    }

    /** 业务模式不信任上游候选集合，始终返回固定顺序白名单。 */
    public List<String> modelVisibleToolNames(List<String> commonModeSelection) {
        return businessMode
                ? BUSINESS_MODEL_TOOLS
                : commonModeSelection == null ? List.of() : List.copyOf(commonModeSelection);
    }

    /** application_action 自带桌面预览/审批状态机，任何模式都不得再套通用 HITL。 */
    public boolean genericHitlAllowed(String toolName, ApprovalPolicy approvalPolicy) {
        return !"application_action".equals(toolName);
    }
}
