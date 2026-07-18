package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shell 工具。
 *
 * <p>安全边界由 BaBiQSandboxInterceptor 先行判断；本工具负责在当前 turn 的 cwd
 * 下执行命令并截断大输出，避免模型误把后端服务目录当成用户项目目录。</p>
 */
@Component
public class ExecShellTool implements Tool {

    /** 单条命令最长执行时间，避免模型误触发长时间阻塞。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 命令输出最大读取量，防止大日志撑爆模型上下文。 */
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;

    /**
     * 返回协议层工具名，必须和 ReActStrategy/HITL 配置保持一致。
     */
    @Override
    public String name() {
        return "exec_shell";
    }

    /**
     * 执行 shell 命令。
     *
     * @param command 模型请求执行的命令
     * @param toolContext Spring AI 工具上下文，携带当前 turn 的 cwd
     * @return 命令输出或失败原因
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "exec_shell",
            description = "执行 shell 命令",
            resultConverter = DefaultToolCallResultConverter.class)
    public ToolResult execShell(@ToolParam(description = "命令") String command,
                                ToolContext toolContext) {
        if (command == null || command.isBlank()) {
            return ToolResult.failure("Command is blank");
        }
        Process process = null;
        Thread gobbler = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            process = buildProcess(command, toolContext).start();
            gobbler = startGobbler(process.getInputStream(), output);
            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                joinQuietly(gobbler);
                return ToolResult.failure("Command timeout after " + DEFAULT_TIMEOUT_SECONDS + "s");
            }
            joinQuietly(gobbler);
            int exitCode = process.exitValue();
            String result = output.toString(shellOutputCharset());
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

    /**
     * 兼容直接调用工具类的旧测试和调试入口；真实 Agent 调用会传入 ToolContext。
     */
    public ToolResult execShell(String command) {
        return execShell(command, null);
    }

    /**
     * 根据当前操作系统选择 shell 包装命令，并把工作目录切换到当前 turn 的 cwd。
     */
    private ProcessBuilder buildProcess(String command, ToolContext toolContext) {
        ProcessBuilder processBuilder = isWindows()
                ? new ProcessBuilder(List.of("cmd.exe", "/c", command))
                : new ProcessBuilder(List.of("sh", "-c", command));
        processBuilder.redirectErrorStream(true);
        Path workingDirectory = ToolPathResolver.workingDirectory(toolContext);
        return processBuilder.directory(workingDirectory.toFile());
    }

    /**
     * Windows 的 {@code cmd.exe} 管道输出使用本机代码页，而不是 JVM 的 {@code file.encoding}。
     */
    private Charset shellOutputCharset() {
        return shellOutputCharset(
                isWindows(),
                System.getProperty("stdout.encoding"),
                System.getProperty("native.encoding"),
                Charset.defaultCharset());
    }

    static Charset shellOutputCharset(boolean windows,
                                      String stdoutEncoding,
                                      String nativeEncoding,
                                      Charset fallback) {
        if (!windows) {
            return StandardCharsets.UTF_8;
        }
        Charset stdoutCharset = supportedCharset(stdoutEncoding);
        if (stdoutCharset != null) {
            return stdoutCharset;
        }
        Charset nativeCharset = supportedCharset(nativeEncoding);
        return nativeCharset != null ? nativeCharset : fallback;
    }

    private static Charset supportedCharset(String charsetName) {
        if (charsetName == null || charsetName.isBlank()) {
            return null;
        }
        try {
            return Charset.forName(charsetName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
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
                // 进程被销毁时可能正常出现，不需要上抛。
            }
        }, "exec-shell-gobbler");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 等待 gobbler 最多 1 秒，避免命令已结束但读取线程短暂未退出时直接丢输出。
     */
    private void joinQuietly(Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join(Duration.ofSeconds(1).toMillis());
        }
    }
}
