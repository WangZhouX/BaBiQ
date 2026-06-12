package com.wzx.babiq.server.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicOAuthCredentialSourceTest {

    @Test
    @DisplayName("accessToken 从 ant CLI 读取并在 TTL 内缓存")
    void access_token_should_read_from_ant_cli_and_cache_within_ttl() {
        FakeAntCliRunner runner = new FakeAntCliRunner();
        runner.enqueue(new AntCliResult(0, "oauth-token\n", ""));
        AnthropicOAuthCredentialSource source = new AnthropicOAuthCredentialSource(
                runner,
                Clock.fixed(Instant.parse("2026-06-11T10:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));

        assertThat(source.accessToken()).isEqualTo("oauth-token");
        assertThat(source.accessToken()).isEqualTo("oauth-token");

        assertThat(runner.calls()).containsExactly(List.of("auth", "print-credentials", "--access-token"));
    }

    @Test
    @DisplayName("accessToken 在 TTL 过期后重新读取 ant CLI")
    void access_token_should_refetch_from_ant_cli_after_ttl_expiry() {
        FakeAntCliRunner runner = new FakeAntCliRunner();
        runner.enqueue(new AntCliResult(0, "oauth-token-1\n", ""));
        runner.enqueue(new AntCliResult(0, "oauth-token-2\n", ""));
        MutableClock clock = new MutableClock(Instant.parse("2026-06-11T10:00:00Z"));
        AnthropicOAuthCredentialSource source = new AnthropicOAuthCredentialSource(
                runner,
                clock,
                Duration.ofMinutes(5));

        assertThat(source.accessToken()).isEqualTo("oauth-token-1");
        clock.advance(Duration.ofMinutes(6));
        assertThat(source.accessToken()).isEqualTo("oauth-token-2");

        assertThat(runner.calls()).containsExactly(
                List.of("auth", "print-credentials", "--access-token"),
                List.of("auth", "print-credentials", "--access-token"));
    }

    @Test
    @DisplayName("accessToken 在 ant CLI 未登录时返回可读错误且不泄露 stderr")
    void access_token_should_throw_readable_error_when_cli_is_not_logged_in() {
        FakeAntCliRunner runner = new FakeAntCliRunner();
        runner.enqueue(new AntCliResult(1, "", "token sk-secret missing"));
        AnthropicOAuthCredentialSource source = new AnthropicOAuthCredentialSource(
                runner,
                Clock.fixed(Instant.parse("2026-06-11T10:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));

        assertThatThrownBy(source::accessToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ant auth login")
                .hasMessageNotContaining("sk-secret");
    }

    @Test
    @DisplayName("status 区分 CLI 是否安装和 OAuth 是否已登录")
    void status_should_report_cli_installation_and_login_state() {
        FakeAntCliRunner runner = new FakeAntCliRunner();
        runner.enqueue(new AntCliResult(0, "ant 0.1.0\n", ""));
        runner.enqueue(new AntCliResult(0, "oauth-token\n", ""));
        AnthropicOAuthCredentialSource source = new AnthropicOAuthCredentialSource(
                runner,
                Clock.fixed(Instant.parse("2026-06-11T10:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));

        AnthropicOAuthStatus status = source.status();

        assertThat(status.cliInstalled()).isTrue();
        assertThat(status.loggedIn()).isTrue();
        assertThat(status.message()).contains("已登录");
    }

    @Test
    @DisplayName("status 遇到 Apache Ant 时提示配置 Anthropic CLI 路径")
    void status_should_reject_apache_ant_binary() {
        FakeAntCliRunner runner = new FakeAntCliRunner();
        runner.enqueue(new AntCliResult(0, "Apache Ant(TM) version 1.10.14 compiled on May 16 2024\n", ""));
        AnthropicOAuthCredentialSource source = new AnthropicOAuthCredentialSource(
                runner,
                Clock.fixed(Instant.parse("2026-06-11T10:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));

        AnthropicOAuthStatus status = source.status();

        assertThat(status.cliInstalled()).isFalse();
        assertThat(status.loggedIn()).isFalse();
        assertThat(status.message()).contains("Apache Ant").contains("cli-path");
        assertThat(runner.calls()).containsExactly(List.of("--version"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class FakeAntCliRunner implements AntCliRunner {
        private final List<AntCliResult> results = new ArrayList<>();
        private final List<List<String>> calls = new ArrayList<>();

        void enqueue(AntCliResult result) {
            results.add(result);
        }

        List<List<String>> calls() {
            return calls;
        }

        @Override
        public AntCliResult run(List<String> arguments) {
            calls.add(List.copyOf(arguments));
            return results.removeFirst();
        }
    }
}
