package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Path;

/**
 * 本地工具路径解析器。
 *
 * <p>BaBiQ 的模型工具接收的是协议参数，模型经常会传入 "."、"index.html" 这类相对路径。
 * 这些路径不能按后端 JVM 进程目录解释，而应按当前 turn 的工作区 cwd 解释；ReActStrategy
 * 已把 cwd 放进 Spring AI ToolContext，本类负责让本地工具统一读取这份上下文。</p>
 */
final class ToolPathResolver {

    private ToolPathResolver() {
    }

    /**
     * 将模型传入的路径解析为本机路径。
     *
     * @param rawPath 模型工具参数中的原始路径；绝对路径保持原语义，相对路径按当前 cwd 解析
     * @param toolContext Spring AI 传入的工具上下文；为空时退回 JVM 进程目录，兼容旧单元测试
     * @return 已 normalize 的路径
     */
    static Path resolvePath(String rawPath, ToolContext toolContext) {
        Path candidate = Path.of(rawPath);
        if (candidate.isAbsolute()) {
            return candidate.normalize();
        }
        return workingDirectory(toolContext).resolve(candidate).normalize();
    }

    /**
     * 解析当前工具调用的工作目录。
     *
     * @param toolContext Spring AI 工具上下文，包含 ReActStrategy 写入的 babiq.cwd
     * @return 当前工具调用应使用的工作目录
     */
    static Path workingDirectory(ToolContext toolContext) {
        Object cwd = toolContext == null ? null : toolContext.getContext().get(BaBiQSandboxInterceptor.CONTEXT_CWD);
        if (cwd == null || cwd.toString().isBlank()) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        return Path.of(cwd.toString()).toAbsolutePath().normalize();
    }
}
