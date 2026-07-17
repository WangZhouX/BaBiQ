package com.wzx.babiq.server.context.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证上下文输入不会通过数组访问器泄漏可变的内部工具快照。 */
class ContextWindowRuntimeInputTest {

    @Test
    void toolCallbacksAccessorReturnsADefensiveCopy() {
        ToolCallback original = mock(ToolCallback.class);
        ContextWindowRuntimeInput input = new ContextWindowRuntimeInput(
                "thread", "turn", "input", "provider", "model", ".", "project",
                null, 128_000, new ToolCallback[]{original});

        ToolCallback[] firstRead = input.toolCallbacks();
        firstRead[0] = mock(ToolCallback.class);

        assertThat(input.toolCallbacks()).containsExactly(original);
        assertThat(input.toolCallbacks()).isNotSameAs(firstRead);
    }
}
