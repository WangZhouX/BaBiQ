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
 * 写文件工具。
 *
 * <p>工具只负责实际 IO；是否允许写入由 BaBiQSandboxInterceptor 在执行前统一判断。
 * 相对路径按 ToolContext 中的 cwd 解析，保证“当前工作目录下创建文件”真的落在当前项目。</p>
 */
@Component
public class WriteFileTool implements Tool {

    /**
     * 返回协议层工具名，必须和 HITL 审批配置保持一致。
     */
    @Override
    public String name() {
        return "write_file";
    }

    /**
     * 将内容写入文件。
     *
     * @param path 模型传入的文件路径；相对路径以当前 turn 的 cwd 为基准
     * @param content 文件内容；为空时写入空字符串
     * @param toolContext Spring AI 工具上下文，携带当前 turn 的 cwd
     * @return 写入结果
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "write_file",
            description = "写入文件内容",
            resultConverter = DefaultToolCallResultConverter.class)
    public ToolResult writeFile(@ToolParam(description = "文件路径") String path,
                                @ToolParam(description = "文件内容") String content,
                                ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return ToolResult.failure("Path is blank");
        }
        try {
            Path file = ToolPathResolver.resolvePath(path, toolContext);
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content == null ? "" : content);
            return ToolResult.ok("Wrote " + file);
        } catch (IOException exception) {
            return ToolResult.failure("IO error: " + exception.getMessage());
        }
    }

    /**
     * 兼容直接调用工具类的旧测试和调试入口；真实 Agent 调用会传入 ToolContext。
     */
    public ToolResult writeFile(String path, String content) {
        return writeFile(path, content, null);
    }
}
