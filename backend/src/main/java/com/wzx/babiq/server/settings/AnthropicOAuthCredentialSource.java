package com.wzx.babiq.server.settings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 通过 Anthropic ant CLI 获取 OAuth access token。
 */
@Component
public class AnthropicOAuthCredentialSource {

    private final AntCliRunner runner;
    private final Clock clock;
    private final Duration tokenTtl;
    /** 最近一次从 Anthropic CLI 读取到的 access token；只放内存，不落 SQLite。 */
    private String cachedToken;
    /** cachedToken 的本地过期时间；超过该时间后下一次请求会重新调用 CLI。 */
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    public AnthropicOAuthCredentialSource(AntCliRunner runner,
                                          @Value("${babiq.anthropic.oauth.token-cache-seconds:300}")
                                          long tokenCacheSeconds) {
        this(runner, Clock.systemUTC(), Duration.ofSeconds(Math.max(1, tokenCacheSeconds)));
    }

    AnthropicOAuthCredentialSource(AntCliRunner runner, Clock clock, Duration tokenTtl) {
        this.runner = runner;
        this.clock = clock;
        this.tokenTtl = tokenTtl;
    }

    /**
     * 返回当前 OAuth access token。
     */
    public synchronized String accessToken() {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(expiresAt)) {
            return cachedToken;
        }
        AntCliResult result = runner.run(List.of("auth", "print-credentials", "--access-token"));
        String token = firstNonBlankLine(result.stdout());
        if (result.exitCode() != 0 || token == null) {
            throw new IllegalStateException("Anthropic OAuth 未登录，请先运行 ant auth login；"
                    + "如果当前 ant 命令是 Apache Ant，请配置 babiq.anthropic.oauth.cli-path 指向 Anthropic CLI");
        }
        cachedToken = token;
        expiresAt = now.plus(tokenTtl);
        return cachedToken;
    }

    /**
     * 查询本机 ant CLI 和登录状态。
     */
    public AnthropicOAuthStatus status() {
        AntCliResult version = runner.run(List.of("--version"));
        if (version.exitCode() != 0) {
            return new AnthropicOAuthStatus(false, false,
                    "未找到 Anthropic ant CLI，请先安装；如果已安装，请配置 babiq.anthropic.oauth.cli-path");
        }
        if (!looksLikeAnthropicCli(version.stdout() + "\n" + version.stderr())) {
            return new AnthropicOAuthStatus(false, false,
                    "当前 ant 命令不像 Anthropic CLI，可能是 Apache Ant；"
                            + "请配置 babiq.anthropic.oauth.cli-path 指向 Anthropic CLI");
        }
        try {
            accessToken();
            return new AnthropicOAuthStatus(true, true, "Anthropic OAuth 已登录");
        } catch (IllegalStateException exception) {
            return new AnthropicOAuthStatus(true, false,
                    "Anthropic OAuth 未登录，请先运行 ant auth login；如命中 Apache Ant 请配置 cli-path");
        }
    }

    /**
     * 清空内存 token 缓存。
     */
    public synchronized void invalidate() {
        cachedToken = null;
        expiresAt = Instant.EPOCH;
    }

    private static String firstNonBlankLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static boolean looksLikeAnthropicCli(String versionOutput) {
        String firstLine = firstNonBlankLine(versionOutput);
        if (firstLine == null) {
            return false;
        }
        String normalized = firstLine.toLowerCase();
        return !normalized.contains("apache ant")
                && (normalized.startsWith("ant ") || normalized.contains("anthropic"));
    }
}
