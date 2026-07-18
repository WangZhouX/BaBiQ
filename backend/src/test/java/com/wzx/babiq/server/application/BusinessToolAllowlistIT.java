package com.wzx.babiq.server.application;

import com.wzx.babiq.server.agent.ReActStrategy;
import com.wzx.babiq.server.application.policy.BusinessAgentModePolicy;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.capability.CapabilityExposurePlan;
import com.wzx.babiq.server.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("business-desktop")
@SpringBootTest
class BusinessToolAllowlistIT {

    private static final Path RUNTIME = Path.of(
            "target", "business-tool-allowlist-it-" + UUID.randomUUID()).toAbsolutePath().normalize();
    private static final Path TOKEN_FILE = RUNTIME.resolve("session-token");

    static {
        try {
            Files.createDirectories(RUNTIME);
            Files.writeString(TOKEN_FILE, "T".repeat(43), StandardCharsets.US_ASCII);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void businessRuntime(DynamicPropertyRegistry registry) {
        registry.add("babiq.business.runtime-dir", RUNTIME::toString);
        registry.add("babiq.business.session-token-file", TOKEN_FILE::toString);
    }

    @Autowired
    private ReActStrategy strategy;

    @Autowired
    private ToolRegistry tools;

    @Autowired
    private BusinessAgentModePolicy policy;

    @Test
    void springContextExposesExactlyTheTwoTrustedBusinessToolsDespiteAHostileCapabilityPlan() {
        assertThat(policy.businessMode()).isTrue();
        assertThat(tools.names()).contains("read_file", "write_file", "exec_shell",
                "orchestrate_flow", "coordinate_team", "explorer");
        CapabilityExposurePlan hostile = new CapabilityExposurePlan(
                List.of("local.read_file", "local.write_file", "local.exec_shell",
                        "mcp.crm.search", "skill.case", "local.orchestrate_flow",
                        "local.coordinate_team", "local.work_unit_manage", "local.explorer"),
                List.of("read_file", "write_file", "exec_shell", "mcp.crm.search", "skill.case",
                        "orchestrate_flow", "coordinate_team", "work_unit_manage", "explorer"),
                List.of(), List.of(), "hostile integration plan");

        List<String> visible = Arrays.stream(strategy.currentToolCallbacks(hostile))
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertThat(visible).containsExactly("application_action", "update_plan");
        assertThat(visible).doesNotContain("read_file", "write_file", "exec_shell", "mcp.crm.search",
                "skill.case", "orchestrate_flow", "coordinate_team", "work_unit_manage", "explorer");
        ToolCallback[] trusted = tools.requiredLocalCallbacksForNames(List.of("application_action", "update_plan"));
        assertThat(strategy.currentToolCallbacks(hostile)).containsExactly(trusted);
    }

    @Test
    void applicationActionNeverEntersTheGenericHitlSet() {
        @SuppressWarnings("unchecked")
        List<String> always = ReflectionTestUtils.invokeMethod(
                strategy, "approvalToolNamesFor", ApprovalPolicy.ALWAYS);
        @SuppressWarnings("unchecked")
        List<String> onRequest = ReflectionTestUtils.invokeMethod(
                strategy, "approvalToolNamesFor", ApprovalPolicy.ON_REQUEST);

        assertThat(always).doesNotContain("application_action");
        assertThat(onRequest).doesNotContain("application_action");
    }
}
