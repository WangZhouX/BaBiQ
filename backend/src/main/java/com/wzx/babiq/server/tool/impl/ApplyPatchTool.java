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
 * 简化版 apply_patch 工具。
 *
 * <p>P1 阶段先按“整文件替换”实现，后续再升级到真正 diff。相对路径按当前 turn 的
 * cwd 解析，避免补丁写到后端进程目录。</p>
 */
@Component
public class ApplyPatchTool implements Tool {

    /**
     * 返回协议层工具名，必须和 HITL 审批配置保持一致。
     */
    @Override
    public String name() {
        return "apply_patch";
    }

    /**
     * 用新内容覆盖目标文件。
     *
     * @param path 模型传入的文件路径；相对路径以当前 turn 的 cwd 为基准
     * @param newContent 新文件内容；为空时写入空字符串
     * @param toolContext Spring AI 工具上下文，携带当前 turn 的 cwd
     * @return 应用结果
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "apply_patch",
            description = "应用补丁",
            resultConverter = DefaultToolCallResultConverter.class)
    public ToolResult applyPatch(@ToolParam(description = "文件路径") String path,
                                 @ToolParam(description = "新内容") String newContent,
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
            Files.writeString(file, newContent == null ? "" : newContent);
            return ToolResult.ok("Patched " + file);
        } catch (IOException exception) {
            return ToolResult.failure("IO error: " + exception.getMessage());
        }
    }

    /**
     * 兼容直接调用工具类的旧测试和调试入口；真实 Agent 调用会传入 ToolContext。
     */
    public ToolResult applyPatch(String path, String newContent) {
        return applyPatch(path, newContent, null);
    }
}
