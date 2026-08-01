package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.policy.BusinessAgentModePolicy;
import com.wzx.babiq.server.approval.ApprovalRuleService;
import com.wzx.babiq.server.hook.BaBiQTokenUsageHook;
import com.wzx.babiq.server.hook.ResumeJumpCleanupHook;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.interceptor.BaBiQStreamingTokenUsageInterceptor;
import com.wzx.babiq.server.interceptor.SpotlightingToolInterceptor;
import com.wzx.babiq.server.interceptor.ToolObservationInterceptor;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ReActStrategy 单元测试。
 *
 * <p>该类专门覆盖 BaBiQ 对 Spring AI Alibaba ReactAgent 的配置装配逻辑。
 * 这里不启动真实模型，只检查 RunnableConfig 这种轻量对象，避免测试依赖外部 API。</p>
 */
class ReActStrategyTest {

    @Test
    void build_resume_config_should_keep_interruption_metadata_as_human_feedback() {
        ReActStrategy strategy = newStrategy();
        InterruptionMetadata feedback = approvedWriteFileFeedback();
        TurnObservationContext context = TurnObservationContext.start("thr_1", "turn_1", "provider-a", "qwen-plus", () -> 0L);
        AgentRunPolicy runPolicy = AgentRunPolicy.of(SandboxMode.READ_ONLY, ApprovalPolicy.ON_REQUEST);

        RunnableConfig config = strategy.buildResumeConfig("thr_1", feedback, "H:\\aaa", null, context, runPolicy);

        assertThat(config.threadId()).contains("thr_1");
        assertThat(config.metadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY).orElseThrow())
                .isSameAs(feedback);
        assertThat(config.metadata(TurnObservationContext.METADATA_KEY).orElseThrow())
                .isSameAs(context);
        assertThat(config.metadata(BaBiQSandboxInterceptor.CONTEXT_CWD).orElseThrow())
                .isEqualTo("H:\\aaa");
        assertThat(config.metadata(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE).orElseThrow())
                .isEqualTo("READ_ONLY");
    }

    @Test
    void build_config_should_carry_turn_runtime_sandbox_mode() {
        ReActStrategy strategy = newStrategy();
        TurnObservationContext context = TurnObservationContext.start("thr_1", "turn_1", "provider-a", "qwen-plus", () -> 0L);
        AgentRunPolicy runPolicy = AgentRunPolicy.of(SandboxMode.DANGER_FULL_ACCESS, ApprovalPolicy.NEVER);

        RunnableConfig config = strategy.buildConfig("thr_1", "H:\\aaa", null, context, runPolicy);

        assertThat(config.metadata(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE).orElseThrow())
                .as("工具节点只看 RunnableConfig/toolContext，必须能拿到本轮设置页切换后的沙箱模式")
                .isEqualTo("DANGER_FULL_ACCESS");
    }

    @Test
    void tool_and_runnable_contexts_should_carry_the_same_immutable_business_scope() {
        ReActStrategy strategy = newStrategy();
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop-a", "desktop-session-a", "auth-a", 1,
                "user-a", "tenant-a", "platform-a");
        TurnObservationContext context = TurnObservationContext.start(
                "thr_1", "turn_1", "provider-a", "qwen-plus", scope, () -> 0L);
        AgentRunPolicy runPolicy = AgentRunPolicy.of(SandboxMode.READ_ONLY, ApprovalPolicy.NEVER);

        Map<String, Object> toolContext = strategy.buildToolContext("H:\\aaa", null, context, runPolicy);
        RunnableConfig config = strategy.buildConfig("thr_1", "H:\\aaa", null, context, runPolicy);

        assertThat(toolContext).containsEntry(BusinessIdentityScope.METADATA_KEY, scope);
        assertThat(config.metadata(BusinessIdentityScope.METADATA_KEY)).contains(scope);
    }

    @Test
    void always_policy_should_request_approval_for_every_visible_tool() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.names()).thenReturn(List.of(
                "read_file", "write_file", "exec_shell", "update_plan", "application_action",
                "business_workbench_read", "business_schedule_mutate",
                "orchestrate_flow", "coordinate_team", "mcp.filesystem.read_text_file"));
        ReActStrategy strategy = newStrategy(registry);

        assertThat(strategy.approvalToolNamesFor(ApprovalPolicy.ALWAYS))
                .containsExactly(
                        "read_file", "write_file", "exec_shell",
                        "business_workbench_read", "business_schedule_mutate",
                        "orchestrate_flow", "coordinate_team", "mcp.filesystem.read_text_file");
        assertThat(strategy.approvalToolNamesFor(ApprovalPolicy.ON_REQUEST))
                .containsExactly(
                        "write_file", "exec_shell", "apply_patch",
                        "business_schedule_mutate",
                        "orchestrate_flow", "coordinate_team", "mcp.filesystem.read_text_file");
        assertThat(strategy.approvalToolNamesFor(ApprovalPolicy.NEVER)).isEmpty();
        assertThat(strategy.approvalToolNamesFor(ApprovalPolicy.ALWAYS)).doesNotContain("application_action");
        assertThat(strategy.approvalToolNamesFor(ApprovalPolicy.ON_REQUEST)).doesNotContain("application_action");
    }

    @Test
    void business_schedule_mutation_uses_a_business_specific_approval_description() throws Exception {
        ReActStrategy strategy = newStrategy(mock(ToolRegistry.class));
        var method = ReActStrategy.class.getDeclaredMethod(
                "approvalDescription", String.class, ApprovalPolicy.class);
        method.setAccessible(true);

        assertThat(method.invoke(strategy, "business_schedule_mutate", ApprovalPolicy.ON_REQUEST))
                .isEqualTo("修改工作台日程需要确认");
    }

    @Test
    void business_mode_restricts_even_a_caller_supplied_exposure_plan() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.names()).thenReturn(List.of(
                "read_file", "application_action", "update_plan", "explorer", "mcp.crm.search"));
        ReActStrategy strategy = newStrategy(registry, new BusinessAgentModePolicy(true));

        assertThat(strategy.modelVisibleToolNames(new com.wzx.babiq.server.capability.CapabilityExposurePlan(
                List.of("local.read_file", "local.application_action", "local.update_plan", "mcp.crm.search"),
                List.of("read_file", "application_action", "update_plan", "mcp.crm.search"),
                List.of(), List.of(), "forged")))
                .containsExactly(
                        "application_action",
                        "business_workbench_read",
                        "business_schedule_mutate",
                        "update_plan");
    }

    @Test
    void business_mode_uses_the_business_system_prompt_without_changing_common_mode() {
        assertThat(newStrategy(mock(ToolRegistry.class), new BusinessAgentModePolicy(true)).systemPrompt())
                .isEqualTo(com.wzx.babiq.server.security.SystemPromptSecurityRule.BUSINESS_PROMPT);
        assertThat(newStrategy(mock(ToolRegistry.class), new BusinessAgentModePolicy(false)).systemPrompt())
                .isEqualTo(com.wzx.babiq.server.security.SystemPromptSecurityRule.PROMPT);
    }

    @Test
    void common_mode_without_an_exposure_plan_keeps_the_original_all_callbacks_path() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolCallback first = mock(ToolCallback.class);
        ToolCallback second = mock(ToolCallback.class);
        when(registry.allCallbacks()).thenReturn(new ToolCallback[]{first, second});

        assertThat(newStrategy(registry).currentToolCallbacks(null)).containsExactly(first, second);
    }

    @Test
    void business_mode_uses_only_registry_verified_trusted_callbacks() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolCallback action = mock(ToolCallback.class);
        ToolCallback workbench = mock(ToolCallback.class);
        ToolCallback schedule = mock(ToolCallback.class);
        ToolCallback plan = mock(ToolCallback.class);
        when(registry.requiredLocalCallbacksForNames(List.of(
                "application_action",
                "business_workbench_read",
                "business_schedule_mutate",
                "update_plan")))
                .thenReturn(new ToolCallback[]{action, workbench, schedule, plan});

        assertThat(newStrategy(registry, new BusinessAgentModePolicy(true)).currentToolCallbacks(null))
                .containsExactly(action, workbench, schedule, plan);
    }

    /**
     * 创建只用于配置测试的策略对象。
     *
     * <p>buildResumeConfig 不会读取这些协作者，所以这里使用 mock 只满足构造函数依赖，
     * 测试重点保持在 RunnableConfig 的元数据装配顺序上。</p>
     */
    private ReActStrategy newStrategy() {
        return newStrategy(mock(ToolRegistry.class));
    }

    private ReActStrategy newStrategy(ToolRegistry toolRegistry) {
        return newStrategy(toolRegistry, new BusinessAgentModePolicy(false));
    }

    private ReActStrategy newStrategy(ToolRegistry toolRegistry, BusinessAgentModePolicy businessPolicy) {
        return new ReActStrategy(
                mock(ChatClientFactory.class),
                toolRegistry,
                mock(AgentLoopProperties.class),
                mock(BaBiQSandboxInterceptor.class),
                mock(ToolObservationInterceptor.class),
                mock(SpotlightingToolInterceptor.class),
                mock(BaBiQTokenUsageHook.class),
                mock(ResumeJumpCleanupHook.class),
                mock(BaBiQStreamingTokenUsageInterceptor.class),
                mock(ApprovalRuleService.class),
                mock(TurnPersistenceService.class),
                null,
                businessPolicy);
    }

    /**
     * 构造一份“用户已批准写文件”的 HITL 反馈。
     *
     * <p>真实链路中它来自 approval/respond，这里保留 write_file 场景，
     * 因为本次 bug 就是在写文件审批通过后恢复 Agent 时暴露的。</p>
     */
    private InterruptionMetadata approvedWriteFileFeedback() {
        InterruptionMetadata.ToolFeedback feedback = InterruptionMetadata.ToolFeedback.builder()
                .id("call_1")
                .name("write_file")
                .arguments("{\"path\":\"hello.html\"}")
                .result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED)
                .build();
        return InterruptionMetadata.builder("hitl", new OverAllState())
                .addToolFeedback(feedback)
                .build();
    }
}
