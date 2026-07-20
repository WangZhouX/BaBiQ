package com.wzx.babiq.server.context.runtime;

import com.wzx.babiq.server.attachment.AttachmentTextSegment;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void attachmentTextSegmentsAreImmutable() {
        List<AttachmentTextSegment> source = new ArrayList<>(List.of(new AttachmentTextSegment(
                "018fb799-2b03-7e7b-8f4c-4df90bc8c289",
                "A-7K3M2Q",
                "合同.txt",
                "text/plain",
                "正文")));
        ContextWindowRuntimeInput input = new ContextWindowRuntimeInput(
                "thread", "turn", "text", "provider", "model", "cwd", "project",
                null, 1_000, new ToolCallback[0], null, BusinessIdentityScope.UNSCOPED, source);

        source.clear();

        assertThat(input.attachmentTextSegments()).hasSize(1);
        assertThatThrownBy(() -> input.attachmentTextSegments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
