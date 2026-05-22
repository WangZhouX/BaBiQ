package com.wzx.babiq.server.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Spring AI Alibaba Agent Framework 已经进入测试类路径。
 *
 * <p>P1-3a 的 ReAct、HITL、MemorySaver、工具拦截器都依赖这些类。这里故意只做
 * {@code Class.forName} 级别的烟测，不触发 Spring 上下文，避免把依赖问题和业务装配问题混在一起。</p>
 */
class AgentFrameworkSmokeTest {

    /**
     * 确认 P1-3a 需要的 SAA Agent Framework 核心类可加载。
     */
    @Test
    void core_classes_present_on_classpath() {
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.ReactAgent")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.hook.ModelHook")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.hook.AgentHook")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.hook.HookPosition")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.action.InterruptionMetadata")).isTrue();
    }

    private static boolean loadable(String className) {
        try {
            Class.forName(className, false, AgentFrameworkSmokeTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
