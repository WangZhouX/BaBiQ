package com.wzx.babiq.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApplicationYamlSecretDefaultTest {

    private static final Pattern ENV_API_KEY =
            Pattern.compile("api-key:\\s*\\$\\{[^:}]+:([^}]*)}");

    @Test
    @DisplayName("application.yml 的 API Key 环境变量不得带仓库内默认密钥")
    void apiKeyEnvironmentPlaceholdersMustFailClosed() throws IOException {
        String yaml;
        try (var input = requireNonNull(
                getClass().getClassLoader().getResourceAsStream("application.yml"))) {
            yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        Matcher matcher = ENV_API_KEY.matcher(yaml);
        int placeholders = 0;
        while (matcher.find()) {
            placeholders++;
            assertThat(matcher.group(1))
                    .as("API Key environment placeholder default")
                    .isEmpty();
        }
        assertThat(placeholders).isGreaterThanOrEqualTo(3);
    }

    private static <T> T requireNonNull(T value) {
        assertThat(value).as("application.yml classpath resource").isNotNull();
        return value;
    }
}
