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

/**
 * 只读文件工具。
 *
 * <p>读取工具本身只做文件形态校验；写入边界由沙箱拦截器控制。相对路径必须按当前
 * turn 的 cwd 解析，否则历史会话切换工作区后会误读后端进程目录。</p>
 */
@Component
public class ReadFileTool implements Tool {

    /**
     * 返回协议层工具名，供 ToolRegistry 和运行记录识别。
     */
    @Override
    public String name() {
        return "read_file";
    }

    /**
     * 读取文件内容。
     *
     * @param path 模型传入的文件路径；相对路径以当前 turn 的 cwd 为基准
     * @param toolContext Spring AI 工具上下文，携带当前 turn 的 cwd
     * @return 文件文本内容，或可反馈给模型的失败原因
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "read_file",
            description = "读取文件内容",
            resultConverter = DefaultToolCallResultConverter.class)
    public ToolResult readFile(@ToolParam(description = "文件路径") String path,
                               ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return ToolResult.failure("Path is blank");
        }
        try {
            Path file = ToolPathResolver.resolvePath(path, toolContext);
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

    /**
     * 兼容直接调用工具类的旧测试和调试入口；真实 Agent 调用会传入 ToolContext。
     */
    public ToolResult readFile(String path) {
        return readFile(path, null);
    }
}
