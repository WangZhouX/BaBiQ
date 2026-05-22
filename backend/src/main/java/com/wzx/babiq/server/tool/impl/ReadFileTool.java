package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 只读文件工具。
 */
@Component
public class ReadFileTool implements Tool {

    @Override
    public String name() {
        return "read_file";
    }

    /**
     * 读取文件内容。
     *
     * @param path 文件路径
     * @return 工具结果
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "read_file",
            description = "读取文件内容",
            resultConverter = DefaultToolCallResultConverter.class)
    public ToolResult readFile(@ToolParam(description = "文件路径") String path) {
        if (path == null || path.isBlank()) {
            return ToolResult.failure("Path is blank");
        }
        try {
            Path file = Path.of(path);
            if (!Files.exists(file)) {
                return ToolResult.failure("File not found: " + path);
            }
            if (!Files.isRegularFile(file)) {
                return ToolResult.failure("Not a regular file: " + path);
            }
            return ToolResult.ok(Files.readString(file));
        } catch (IOException exception) {
            return ToolResult.failure("IO error: " + exception.getMessage());
        }
    }
}
