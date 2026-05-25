package com.wzx.babiq.server.api.method;

import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.settings.AppSettings;
import com.wzx.babiq.server.settings.SandboxSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * sandbox/policy 协议测试。
 *
 * <p>桌面端权限 chip 必须展示后端真实沙箱模式，不能再写死“完全访问权限”。</p>
 */
class SandboxPolicyHandlerTest {

    @Test
    @DisplayName("返回后端当前沙箱模式和中文展示名")
    void handle_should_return_current_sandbox_mode_and_label() {
        SandboxPolicyHandler handler = new SandboxPolicyHandler(properties(SandboxMode.DANGER_FULL_ACCESS));

        Object payload = handler.handle(null, null);

        Map<String, Object> response = responseFrom(payload);
        assertThat(response)
                .containsEntry("mode", "DANGER_FULL_ACCESS")
                .containsEntry("label", "完全访问权限");
    }

    @Test
    @DisplayName("工作区可写模式返回工作区可写展示名")
    void handle_should_label_workspace_write_mode() {
        SandboxPolicyHandler handler = new SandboxPolicyHandler(properties(SandboxMode.WORKSPACE_WRITE));

        Object payload = handler.handle(null, null);

        assertThat(responseFrom(payload))
                .containsEntry("mode", "WORKSPACE_WRITE")
                .containsEntry("label", "工作区可写");
    }

    @Test
    @DisplayName("设置沙箱模式后返回桌面端需要的 mode/label 结构")
    void set_handler_should_return_sandbox_policy_shape() {
        SandboxSettingsService service = mock(SandboxSettingsService.class);
        when(service.setMode("READ_ONLY"))
                .thenReturn(new AppSettings("deepseek", "READ_ONLY", "ON_REQUEST", "H:/aaa"));
        SandboxPolicySetHandler handler = new SandboxPolicySetHandler(service);

        Object payload = handler.handle(
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("mode", "READ_ONLY")),
                null);

        assertThat(responseFrom(payload))
                .containsEntry("mode", "READ_ONLY")
                .containsEntry("label", "只读权限")
                .doesNotContainKeys("sandboxMode", "approvalPolicy");
    }

    private static AgentLoopProperties properties(SandboxMode sandboxMode) {
        return new AgentLoopProperties(
                20,
                ApprovalPolicy.ON_REQUEST,
                sandboxMode,
                List.of(),
                new AgentLoopProperties.Tools(new AgentLoopProperties.Output(4000))
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseFrom(Object payload) {
        return (Map<String, Object>) payload;
    }
}
