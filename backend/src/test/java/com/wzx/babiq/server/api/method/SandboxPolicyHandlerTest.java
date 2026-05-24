package com.wzx.babiq.server.api.method;

import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
