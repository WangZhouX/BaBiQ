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

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;

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
            process = buildProcess(command).start();
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

    private ProcessBuilder buildProcess(String command) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder processBuilder = isWindows
                ? new ProcessBuilder(List.of("cmd.exe", "/c", command))
                : new ProcessBuilder(List.of("sh", "-c", command));
        processBuilder.redirectErrorStream(true);
        return processBuilder.directory(Path.of(".").toFile());
    }

    private Thread startGobbler(InputStream inputStream, ByteArrayOutputStream output) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            int total = 0;
            try {
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
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

    private void joinQuietly(Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join(Duration.ofSeconds(1).toMillis());
        }
    }
}
