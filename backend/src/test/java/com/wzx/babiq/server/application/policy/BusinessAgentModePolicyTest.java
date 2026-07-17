package com.wzx.babiq.server.application.policy;

import com.wzx.babiq.server.approval.ApprovalPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessAgentModePolicyTest {

    @Test
    void business_mode_exposes_only_application_action_and_update_plan() {
        BusinessAgentModePolicy policy = new BusinessAgentModePolicy(true);

        assertThat(policy.modelVisibleToolNames(List.of(
                "read_file", "application_action", "application_action", "mcp.crm.search",
                "explorer", "orchestrate_flow", "coordinate_team", "work_unit_manage")))
                .containsExactly("application_action", "update_plan");
    }

    @Test
    void common_mode_preserves_the_existing_tool_selection() {
        BusinessAgentModePolicy policy = new BusinessAgentModePolicy(false);
        List<String> selected = List.of("read_file", "mcp.crm.search", "explorer");

        assertThat(policy.modelVisibleToolNames(selected)).containsExactlyElementsOf(selected);
    }

    @Test
    void application_action_never_enters_generic_hitl() {
        BusinessAgentModePolicy policy = new BusinessAgentModePolicy(true);

        for (ApprovalPolicy approvalPolicy : ApprovalPolicy.values()) {
            assertThat(policy.genericHitlAllowed("application_action", approvalPolicy)).isFalse();
        }
    }
}
