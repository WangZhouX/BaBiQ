package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 递归搜索工具。
 */
@Component
public class GrepTool implements Tool {

    @Override
    public String name() {
        return "grep";
    }

    /**
     * 在目录树中递归搜索文本。
     *
     * @param pattern 正则表达式
     * @param root 根目录
     * @return 工具结果
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "grep",
            description = "递归搜索文本",
            resultConverter = DefaultToolCallResultConverter.class)
    public ToolResult grep(@ToolParam(description = "正则表达式") String pattern,
                           @ToolParam(description = "根目录") String root) {
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.failure("Pattern is blank");
        }
        if (root == null || root.isBlank()) {
            return ToolResult.failure("Root path is blank");
        }

        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException exception) {
            return ToolResult.failure("Bad regex: " + exception.getMessage());
        }

        try {
            Path rootPath = Path.of(root);
            if (!Files.exists(rootPath)) {
                return ToolResult.failure("Root not found: " + root);
            }
            List<String> lines = new ArrayList<>();
            try (var stream = Files.walk(rootPath)) {
                stream.filter(Files::isRegularFile).forEach(file -> appendMatches(lines, file, compiled));
            }
            return ToolResult.ok(lines.isEmpty() ? "(no match)" : String.join(System.lineSeparator(), lines));
        } catch (IOException exception) {
            return ToolResult.failure("IO error: " + exception.getMessage());
        }
    }

    private void appendMatches(List<String> lines, Path file, Pattern pattern) {
        try {
            List<String> content = Files.readAllLines(file);
            for (int index = 0; index < content.size(); index++) {
                String line = content.get(index);
                if (pattern.matcher(line).find()) {
                    lines.add(file + ":" + (index + 1) + ":" + line);
                }
            }
        } catch (IOException ignored) {
            // 二进制或不可读文件直接跳过，不中断整个搜索。
        }
    }
}
