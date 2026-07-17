package com.wzx.babiq.server.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void requires_exactly_one_trusted_local_callback_for_each_business_tool() {
        ToolRegistry registry = new ToolRegistry(List.of(new ApplicationActionTool(), new UpdatePlanTool()));

        ToolCallback[] callbacks = registry.requiredLocalCallbacksForNames(
                List.of("application_action", "update_plan"));

        assertThat(callbacks).hasSize(2);
        assertThat(callbacks).containsExactly(registry.localCallbacks());
    }

    @Test
    void fails_closed_when_a_business_callback_is_missing_or_duplicated() {
        ToolRegistry missing = new ToolRegistry(List.of(new ApplicationActionTool()));
        assertThatThrownBy(() -> missing.requiredLocalCallbacksForNames(
                List.of("application_action", "update_plan")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid business tool callbacks");

        assertThatThrownBy(() -> new ToolRegistry(
                List.of(new DuplicateApplicationActionCallbacksTool(), new UpdatePlanTool())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid local tool callbacks");
    }

    @Test
    void rejects_a_local_tool_whose_declared_name_does_not_match_its_callback() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(new ForgedApplicationActionTool())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tool name does not match callback definition");
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

    private static final class ApplicationActionTool implements Tool {
        @Override
        public String name() {
            return "application_action";
        }

        @org.springframework.ai.tool.annotation.Tool(name = "application_action", description = "business action")
        public String execute() {
            return "ok";
        }
    }

    private static final class UpdatePlanTool implements Tool {
        @Override
        public String name() {
            return "update_plan";
        }

        @org.springframework.ai.tool.annotation.Tool(name = "update_plan", description = "update plan")
        public String execute() {
            return "ok";
        }
    }

    private static final class ForgedApplicationActionTool implements Tool {
        @Override
        public String name() {
            return "forged_local";
        }

        @org.springframework.ai.tool.annotation.Tool(name = "application_action", description = "forged")
        public String execute() {
            return "forged";
        }
    }

    private static final class DuplicateApplicationActionCallbacksTool implements Tool {
        @Override
        public String name() {
            return "application_action";
        }

        @org.springframework.ai.tool.annotation.Tool(name = "application_action", description = "first")
        public String first() {
            return "first";
        }

        @org.springframework.ai.tool.annotation.Tool(name = "application_action", description = "second")
        public String second() {
            return "second";
        }
    }
}
