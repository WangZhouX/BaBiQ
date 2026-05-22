package com.wzx.babiq.server.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolResult 用于统一工具调用结果的 JSON 载体。
 */
class ToolResultTest {

    @Test
    void ok_result_has_empty_error_and_not_truncated() {
        ToolResult result = ToolResult.ok("hello");

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).isEqualTo("hello");
        assertThat(result.error()).isNull();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void failure_result_has_empty_output() {
        ToolResult result = ToolResult.failure("boom");

        assertThat(result.ok()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).isEqualTo("boom");
        assertThat(result.truncated()).isFalse();
    }
}
