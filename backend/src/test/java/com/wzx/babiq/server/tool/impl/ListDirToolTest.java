package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ListDirTool 的 P1-3a 验收测试。
 */
class ListDirToolTest {

    private final ListDirTool tool = new ListDirTool();

    @TempDir
    private Path tempDir;

    @Test
    void name_returns_protocol_tool_name() {
        assertThat(tool.name()).isEqualTo("list_dir");
    }

    @Test
    void list_dir_marks_files_and_directories() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a");
        Files.createDirectory(tempDir.resolve("docs"));

        ToolResult result = tool.listDir(tempDir.toString());

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).contains("[F] a.txt", "[D] docs");
    }

    @Test
    void list_dir_rejects_missing_directory() {
        ToolResult result = tool.listDir(tempDir.resolve("missing").toString());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("Directory not found");
    }

    @Test
    void list_dir_rejects_regular_file() throws Exception {
        Path file = tempDir.resolve("plain.txt");
        Files.writeString(file, "plain");

        ToolResult result = tool.listDir(file.toString());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("Not a directory");
    }
}
