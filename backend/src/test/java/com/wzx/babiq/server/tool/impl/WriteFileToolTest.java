package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.chat.model.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WriteFileTool 的 P1-3a 验收测试。
 */
class WriteFileToolTest {

    private final WriteFileTool tool = new WriteFileTool();

    @TempDir
    private Path tempDir;

    @Test
    void name_returns_protocol_tool_name() {
        assertThat(tool.name()).isEqualTo("write_file");
    }

    @Test
    void write_file_writes_text_content() throws Exception {
        Path file = tempDir.resolve("note.txt");

        ToolResult result = tool.writeFile(file.toString(), "hi");

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("hi");
    }

    @Test
    void write_file_resolves_relative_path_against_tool_context_cwd() throws Exception {
        ToolResult result = tool.writeFile("index.html", "hi", toolContext(tempDir));

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(tempDir.resolve("index.html"))).isEqualTo("hi");
    }

    @Test
    void write_file_creates_parent_directories() throws Exception {
        Path file = tempDir.resolve("a/b/c.txt");

        ToolResult result = tool.writeFile(file.toString(), "nested");

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("nested");
    }

    @Test
    void write_file_treats_null_content_as_empty() throws Exception {
        Path file = tempDir.resolve("empty.txt");

        ToolResult result = tool.writeFile(file.toString(), null);

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(file)).isEmpty();
    }

    @Test
    void write_file_rejects_blank_path() {
        ToolResult result = tool.writeFile("", "content");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("blank");
    }

    private ToolContext toolContext(Path cwd) {
        return new ToolContext(Map.of("babiq.cwd", cwd.toString()));
    }
}
