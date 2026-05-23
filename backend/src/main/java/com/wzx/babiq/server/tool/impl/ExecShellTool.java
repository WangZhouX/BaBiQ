package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shell 工具。
 */
@Component
public class ExecShellTool implements Tool {

    /** 单条命令最长执行时间，P1 先固定 30 秒，避免模型误触发长时间阻塞。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 命令输出最大读取量，防止大日志撑爆模型上下文。 */
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;

    /**
     * 工具注册名，需要和 ReActStrategy/HITL 配置保持一致。
     */
    @Override
    public String name() {
        return "exec_shell";
    }

    /**
     * 执行 shell 命令。
     *
     * @param command 命令
     * @return 工具结果
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "exec_shell",
            description = "执行 shell 命令",
            resultConverter = DefaultToolCallResultConverter.class)
    public ToolResult execShell(@ToolParam(description = "命令") String command) {
        if (command == null || command.isBlank()) {
            return ToolResult.failure("Command is blank");
        }
        Process process = null;
        Thread gobbler = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            // ProcessBuilder 只负责启动进程；安全边界在 BaBiQSandboxInterceptor 里先行判断。
            process = buildProcess(command).start();
            // 单独线程读取输出，避免子进程 stdout 缓冲区写满后卡死。
            gobbler = startGobbler(process.getInputStream(), output);
            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                joinQuietly(gobbler);
                return ToolResult.failure("Command timeout after " + DEFAULT_TIMEOUT_SECONDS + "s");
            }
            joinQuietly(gobbler);
            int exitCode = process.exitValue();
            String result = output.toString(StandardCharsets.UTF_8);
            if (exitCode != 0) {
                // 非 0 退出码作为工具失败返回给模型，让模型能根据 stderr/stdout 修正下一步。
                return ToolResult.failure("Exit " + exitCode + ": " + result);
            }
            return ToolResult.ok(result);
        } catch (IOException exception) {
            return ToolResult.failure("Exec error: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return ToolResult.failure("Exec interrupted");
        }
    }

    /**
     * 根据当前操作系统选择 shell 包装命令。
     */
    private ProcessBuilder buildProcess(String command) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder processBuilder = isWindows
                ? new ProcessBuilder(List.of("cmd.exe", "/c", command))
                : new ProcessBuilder(List.of("sh", "-c", command));
        processBuilder.redirectErrorStream(true);
        return processBuilder.directory(Path.of(".").toFile());
    }

    /**
     * 启动后台输出读取线程。
     */
    private Thread startGobbler(InputStream inputStream, ByteArrayOutputStream output) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            int total = 0;
            try {
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
                    // 只写入上限内的字节，但 total 仍记录真实读取量，用于判断是否需要截断提示。
                    int canWrite = Math.min(read, DEFAULT_MAX_OUTPUT_BYTES - total);
                    if (canWrite > 0) {
                        output.write(buffer, 0, canWrite);
                    }
                    total += read;
                    if (total >= DEFAULT_MAX_OUTPUT_BYTES) {
                        output.write("\n...[output truncated]\n".getBytes(StandardCharsets.UTF_8));
                        break;
                    }
                }
            } catch (IOException ignored) {
                // 进程被销毁时正常出现，不需要上抛。
            }
        }, "exec-shell-gobbler");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 等待 gobbler 最多 1 秒，避免命令已结束但读线程短暂未退出时直接丢输出。
     */
    private void joinQuietly(Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join(Duration.ofSeconds(1).toMillis());
        }
    }
}
