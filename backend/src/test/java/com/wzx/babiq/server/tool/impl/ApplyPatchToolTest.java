package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApplyPatchTool 的 P1-3a 验收测试。
 */
class ApplyPatchToolTest {

    private final ApplyPatchTool tool = new ApplyPatchTool();

    @TempDir
    private Path tempDir;

    @Test
    void name_returns_protocol_tool_name() {
        assertThat(tool.name()).isEqualTo("apply_patch");
    }

    @Test
    void apply_patch_replaces_file_content() throws Exception {
        Path file = tempDir.resolve("target.txt");
        Files.writeString(file, "before");

        ToolResult result = tool.applyPatch(file.toString(), "after");

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("after");
    }

    @Test
    void apply_patch_creates_parent_directories() throws Exception {
        Path file = tempDir.resolve("nested/target.txt");

        ToolResult result = tool.applyPatch(file.toString(), "new");

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("new");
    }

    @Test
    void apply_patch_rejects_blank_path() {
        ToolResult result = tool.applyPatch("\t", "new");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("blank");
    }
}
