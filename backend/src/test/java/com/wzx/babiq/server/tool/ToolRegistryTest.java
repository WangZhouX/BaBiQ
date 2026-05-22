package com.wzx.babiq.server.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolRegistry 必须能够发现工具、按名称检索并导出 ToolCallback。
 */
class ToolRegistryTest {

    @Test
    void indexes_tools_and_exports_callbacks() {
        ToolRegistry registry = new ToolRegistry(List.of(new AlphaTool(), new BetaTool()));

        assertThat(registry.names()).containsExactly("alpha", "beta");
        assertThat(registry.get("alpha")).isPresent();

        ToolCallback[] callbacks = registry.allCallbacks();
        assertThat(callbacks).hasSize(2);
        assertThat(List.of(callbacks).get(0).getToolDefinition().name()).isIn("alpha", "beta");
    }

    private static final class AlphaTool implements Tool {
        @Override
        public String name() {
            return "alpha";
        }

        @org.springframework.ai.tool.annotation.Tool(
                name = "alpha",
                description = "alpha tool",
                resultConverter = org.springframework.ai.tool.execution.DefaultToolCallResultConverter.class)
        public String alpha() {
            return "alpha";
        }
    }

    private static final class BetaTool implements Tool {
        @Override
        public String name() {
            return "beta";
        }

        @org.springframework.ai.tool.annotation.Tool(
                name = "beta",
                description = "beta tool",
                resultConverter = org.springframework.ai.tool.execution.DefaultToolCallResultConverter.class)
        public String beta() {
            return "beta";
        }
    }
}
