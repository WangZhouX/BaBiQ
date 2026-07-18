package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.chat.model.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExecShellTool 的 P1-3a 验收测试。
 */
class ExecShellToolTest {

    private final ExecShellTool tool = new ExecShellTool();

    @TempDir
    private Path tempDir;

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
    void exec_shell_preserves_non_ascii_stdout() {
        ToolResult result = tool.execShell("echo 汇泰");

        assertThat(result.ok()).isTrue();
        assertThat(result.output().trim()).isEqualTo("汇泰");
    }

    @Test
    void windows_shell_output_charset_falls_back_to_native_encoding() {
        Charset gbk = Charset.forName("GBK");

        Charset resolved = ExecShellTool.shellOutputCharset(
                true, "not-a-charset", "GBK", StandardCharsets.UTF_8);

        assertThat(resolved).isEqualTo(gbk);
        assertThat(new String("汇泰".getBytes(gbk), resolved)).isEqualTo("汇泰");
    }

    @Test
    void exec_shell_runs_inside_tool_context_cwd() throws Exception {
        ToolResult result = tool.execShell(isWindows() ? "cd" : "pwd", toolContext(tempDir));

        assertThat(result.ok()).isTrue();
        assertThat(Path.of(result.output().trim()).toRealPath()).isEqualTo(tempDir.toRealPath());
    }

    @Test
    void exec_shell_relative_writes_land_inside_tool_context_cwd() throws Exception {
        String command = isWindows() ? "echo hello> created.txt" : "printf hello > created.txt";

        ToolResult result = tool.execShell(command, toolContext(tempDir));

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(tempDir.resolve("created.txt")).trim()).isEqualTo("hello");
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

    private ToolContext toolContext(Path cwd) {
        return new ToolContext(Map.of("babiq.cwd", cwd.toString()));
    }
}
