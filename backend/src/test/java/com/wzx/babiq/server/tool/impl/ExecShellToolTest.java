package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExecShellTool 的 P1-3a 验收测试。
 */
class ExecShellToolTest {

    private final ExecShellTool tool = new ExecShellTool();

    @Test
    void name_returns_protocol_tool_name() {
        assertThat(tool.name()).isEqualTo("exec_shell");
    }

    @Test
    void exec_shell_returns_stdout_for_successful_command() {
        ToolResult result = tool.execShell("echo babiq");

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).contains("babiq");
    }

    @Test
    void exec_shell_rejects_blank_command() {
        ToolResult result = tool.execShell(" ");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("blank");
    }

    @Test
    void exec_shell_reports_non_zero_exit_code() {
        ToolResult result = tool.execShell(isWindows() ? "exit /b 7" : "exit 7");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("Exit 7");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
