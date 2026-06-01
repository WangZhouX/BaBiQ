package com.wzx.babiq.server.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统提示词回归测试。
 *
 * <p>P4 的计划可视化只靠模型按提示词决定是否调用 update_plan，
 * 这里固定“简单任务不用计划、复杂任务才用计划”的规则确实注入到了系统提示中。</p>
 */
class SystemPromptSecurityRuleTest {

    @Test
    void prompt_should_include_update_plan_usage_rules_without_replacing_security_rules() {
        assertThat(SystemPromptSecurityRule.PROMPT)
                .contains("update_plan")
                .contains("简单")
                .contains("最多一步")
                .contains("不要在正文重复")
                .contains("untrusted-data");
    }

    @Test
    void prompt_should_include_explorer_delegation_boundaries() {
        assertThat(SystemPromptSecurityRule.PROMPT)
                .contains("explorer")
                .contains("READ-ONLY")
                .contains("只读")
                .contains("委派")
                .contains("untrusted-data");
    }

    @Test
    void prompt_should_include_flow_orchestration_boundaries() {
        assertThat(SystemPromptSecurityRule.PROMPT)
                .contains("orchestrate_flow")
                .contains("SequentialAgent")
                .contains("ParallelAgent")
                .contains("RoutingAgent")
                .contains("运行前整体审批")
                .contains("不要用于简单单步任务");
    }

    @Test
    void prompt_should_include_team_coordination_boundaries() {
        assertThat(SystemPromptSecurityRule.PROMPT)
                .contains("coordinate_team")
                .contains("supervisor")
                .contains("团队协作")
                .contains("最大调度轮数")
                .contains("FINISH")
                .contains("不要用于简单单步任务");
    }
}
