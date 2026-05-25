package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * 列目录工具。
 *
 * <p>模型可以用它查看当前工作区内的文件。相对路径会按 ToolContext 中的 cwd 解析，
 * 避免 "." 被误解为后端服务进程的启动目录。</p>
 */
@Component
public class ListDirTool implements Tool {

    /**
     * 返回协议层工具名，必须和 ReActStrategy、审批配置中的名称保持一致。
     */
    @Override
    public String name() {
        return "list_dir";
    }

    /**
     * 列出目录内容。
     *
     * @param path 模型传入的目录路径；相对路径以当前 turn 的 cwd 为基准
     * @param toolContext Spring AI 工具上下文，携带当前 turn 的 cwd
     * @return 目录条目列表，文件用 [F] 标记，目录用 [D] 标记
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "list_dir",
            description = "列出目录内容",
            resultConverter = DefaultToolCallResultConverter.class)
    public ToolResult listDir(@ToolParam(description = "目录路径") String path,
                              ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return ToolResult.failure("Path is blank");
        }
        try {
            Path dir = ToolPathResolver.resolvePath(path, toolContext);
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

    /**
     * 兼容直接调用工具类的旧测试和调试入口；真实 Agent 调用会传入 ToolContext。
     */
    public ToolResult listDir(String path) {
        return listDir(path, null);
    }
}
