package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GrepTool 的 P1-3a 验收测试。
 */
class GrepToolTest {

    private final GrepTool tool = new GrepTool();

    @TempDir
    private Path tempDir;

    @Test
    void name_returns_protocol_tool_name() {
        assertThat(tool.name()).isEqualTo("grep");
    }

    @Test
    void grep_returns_matching_file_line_and_text() throws Exception {
        Path file = tempDir.resolve("notes.txt");
        Files.writeString(file, "alpha\nBaBiQ agent\nomega");

        ToolResult result = tool.grep("BaBiQ", tempDir.toString());

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).contains("notes.txt:2:BaBiQ agent");
    }

    @Test
    void grep_returns_no_match_marker() throws Exception {
        Files.writeString(tempDir.resolve("notes.txt"), "alpha");

        ToolResult result = tool.grep("missing", tempDir.toString());

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).isEqualTo("(no match)");
    }

    @Test
    void grep_rejects_bad_regex() {
        ToolResult result = tool.grep("[", tempDir.toString());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("Bad regex");
    }
}
