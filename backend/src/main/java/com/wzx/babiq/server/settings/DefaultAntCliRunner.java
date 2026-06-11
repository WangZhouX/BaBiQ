package com.wzx.babiq.server.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 运行短生命周期 ant CLI 命令。
 *
 * <p>该 runner 只用于 `ant auth print-credentials --access-token` 等会快速返回的命令。
 * 交互式 `ant auth login` 必须走 {@link DefaultAntCliLoginLauncher}，避免被 15 秒超时杀掉。</p>
 */
@Component
public class DefaultAntCliRunner implements AntCliRunner {

    private final String cliPath;
    private final Duration timeout;

    public DefaultAntCliRunner(@Value("${babiq.anthropic.oauth.cli-path:ant}") String cliPath,
                               @Value("${babiq.anthropic.oauth.command-timeout-seconds:15}") long timeoutSeconds) {
        this.cliPath = cliPath == null || cliPath.isBlank() ? "ant" : cliPath;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    @Override
    public AntCliResult run(List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(cliPath);
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command).start();
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new AntCliResult(124, stdout.join(), "ant CLI 执行超时");
            }
            return new AntCliResult(process.exitValue(), stdout.join(), stderr.join());
        } catch (IOException exception) {
            return new AntCliResult(127, "", exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new AntCliResult(130, "", "ant CLI 执行被中断");
        }
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream input = stream) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                return "";
            }
        });
    }
}
