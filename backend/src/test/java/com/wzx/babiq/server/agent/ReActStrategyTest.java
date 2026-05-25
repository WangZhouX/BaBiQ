package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
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
import com.wzx.babiq.server.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

        RunnableConfig config = strategy.buildResumeConfig("thr_1", feedback, context);

        assertThat(config.threadId()).contains("thr_1");
        assertThat(config.metadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY).orElseThrow())
                .isSameAs(feedback);
        assertThat(config.metadata(TurnObservationContext.METADATA_KEY).orElseThrow())
                .isSameAs(context);
    }

    /**
     * 创建只用于配置测试的策略对象。
     *
     * <p>buildResumeConfig 不会读取这些协作者，所以这里使用 mock 只满足构造函数依赖，
     * 测试重点保持在 RunnableConfig 的元数据装配顺序上。</p>
     */
    private ReActStrategy newStrategy() {
        return new ReActStrategy(
                mock(ChatClientFactory.class),
                mock(ToolRegistry.class),
                mock(AgentLoopProperties.class),
                mock(BaBiQSandboxInterceptor.class),
                mock(ToolObservationInterceptor.class),
                mock(SpotlightingToolInterceptor.class),
                mock(BaBiQTokenUsageHook.class),
                mock(ResumeJumpCleanupHook.class),
                mock(BaBiQStreamingTokenUsageInterceptor.class),
                mock(ApprovalRuleService.class),
                mock(TurnPersistenceService.class));
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
