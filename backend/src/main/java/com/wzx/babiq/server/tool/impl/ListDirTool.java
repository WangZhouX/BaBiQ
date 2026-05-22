package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * 列目录工具。
 */
@Component
public class ListDirTool implements Tool {

    @Override
    public String name() {
        return "list_dir";
    }

    /**
     * 列出目录内容。
     *
     * @param path 目录路径
     * @return 工具结果
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "list_dir",
            description = "列出目录内容",
            resultConverter = DefaultToolCallResultConverter.class)
    public ToolResult listDir(@ToolParam(description = "目录路径") String path) {
        if (path == null || path.isBlank()) {
            return ToolResult.failure("Path is blank");
        }
        try {
            Path dir = Path.of(path);
            if (!Files.exists(dir)) {
                return ToolResult.failure("Directory not found: " + path);
            }
            if (!Files.isDirectory(dir)) {
                return ToolResult.failure("Not a directory: " + path);
            }
            try (var stream = Files.list(dir)) {
                String output = stream
                        .map(candidate -> (Files.isDirectory(candidate) ? "[D] " : "[F] ") + candidate.getFileName())
                        .sorted()
                        .collect(Collectors.joining(System.lineSeparator()));
                return ToolResult.ok(output);
            }
        } catch (IOException exception) {
            return ToolResult.failure("IO error: " + exception.getMessage());
        }
    }
}
