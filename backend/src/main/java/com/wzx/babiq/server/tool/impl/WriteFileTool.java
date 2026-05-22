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
 * 写文件工具。
 */
@Component
public class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "write_file";
    }

    /**
     * 将内容写入文件。
     *
     * @param path 文件路径
     * @param content 文件内容
     * @return 工具结果
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "write_file",
            description = "写入文件内容",
            resultConverter = DefaultToolCallResultConverter.class)
    public ToolResult writeFile(@ToolParam(description = "文件路径") String path,
                                @ToolParam(description = "文件内容") String content) {
        if (path == null || path.isBlank()) {
            return ToolResult.failure("Path is blank");
        }
        try {
            Path file = Path.of(path);
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content == null ? "" : content);
            return ToolResult.ok("Wrote " + path);
        } catch (IOException exception) {
            return ToolResult.failure("IO error: " + exception.getMessage());
        }
    }
}
