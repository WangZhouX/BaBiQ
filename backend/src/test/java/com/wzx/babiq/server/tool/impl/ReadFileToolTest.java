package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReadFileTool 的 P1-3a 验收测试。
 */
class ReadFileToolTest {

    private final ReadFileTool tool = new ReadFileTool();

    @TempDir
    private Path tempDir;

    @Test
    void name_returns_protocol_tool_name() {
        assertThat(tool.name()).isEqualTo("read_file");
    }

    @Test
    void read_file_returns_text_content() throws Exception {
        Path file = tempDir.resolve("README.md");
        Files.writeString(file, "hello BaBiQ");

        ToolResult result = tool.readFile(file.toString());

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).isEqualTo("hello BaBiQ");
    }

    @Test
    void read_file_rejects_blank_path() {
        ToolResult result = tool.readFile(" ");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("blank");
    }

    @Test
    void read_file_rejects_directory() {
        ToolResult result = tool.readFile(tempDir.toString());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("Not a regular file");
    }
}
