package com.wzx.babiq.server.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ToolErrorSanitizationContractTest {
    private static final String SAFE_INTERCEPTOR_ORDER =
            ".interceptors(toolObservationInterceptor, sandboxInterceptor, "
                    + "spotlightingInterceptor, evictionInterceptor)";
    private static final Pattern RAW_AGENT_TOOL_LOGGING =
            Pattern.compile("\\.enableLogging\\s*\\(\\s*true\\s*\\)");

    @Test
    void main_and_sub_agents_sanitize_tool_errors_before_they_return_to_agent_tool_logging()
            throws Exception {
        String mainAgent = Files.readString(Path.of(
                "src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java"));
        String subAgent = Files.readString(Path.of(
                "src/main/java/com/wzx/babiq/server/agent/delegation/SubAgentRuntimeFactory.java"));

        assertThat(mainAgent).contains(SAFE_INTERCEPTOR_ORDER);
        assertThat(subAgent).contains(SAFE_INTERCEPTOR_ORDER);
    }

    @Test
    void raw_agent_tool_acting_logs_remain_disabled() throws Exception {
        StringBuilder sources = new StringBuilder();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sources.append(Files.readString(file)).append('\n');
            }
        }

        assertThat(RAW_AGENT_TOOL_LOGGING.matcher(sources).find()).isFalse();
    }
}
