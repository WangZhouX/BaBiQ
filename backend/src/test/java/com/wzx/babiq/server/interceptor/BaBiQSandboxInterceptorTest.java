package com.wzx.babiq.server.interceptor;

import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BaBiQSandboxInterceptor 单元测试。
 *
 * <p>覆盖 D31 沙箱三档与 D24 读写工具分流，确保工具保持纯 IO 时，写类风险仍在
 * ToolInterceptor 层被统一拦住。</p>
 */
class BaBiQSandboxInterceptorTest {

    @Test
    void read_tools_bypass_sandbox() {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.READ_ONLY);

        assertThat(interceptor.shouldEnforceSandbox("read_file")).isFalse();
        assertThat(interceptor.checkOrReject("read_file", "{}", Map.of())).isNull();
    }

    @Test
    void read_only_rejects_write_tool(@TempDir Path root) {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.READ_ONLY);
        Map<String, Object> context = Map.of(BaBiQSandboxInterceptor.CONTEXT_CWD, root.toString());

        String rejection = interceptor.checkOrReject("write_file",
                "{\"path\":\"" + root.resolve("a.txt").toString().replace("\\", "\\\\") + "\"}",
                context);

        assertThat(rejection).contains("read-only");
    }

    @Test
    void workspace_write_allows_inside_cwd(@TempDir Path root) {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.WORKSPACE_WRITE);
        Map<String, Object> context = Map.of(BaBiQSandboxInterceptor.CONTEXT_CWD, root.toString());

        String rejection = interceptor.checkOrReject("write_file",
                "{\"path\":\"" + root.resolve("a.txt").toString().replace("\\", "\\\\") + "\"}",
                context);

        assertThat(rejection).isNull();
    }

    @Test
    void workspace_write_rejects_outside_cwd(@TempDir Path root) throws Exception {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.WORKSPACE_WRITE);
        Path outside = Files.createTempDirectory("babiq-outside-");
        Map<String, Object> context = Map.of(BaBiQSandboxInterceptor.CONTEXT_CWD, root.toString());

        String rejection = interceptor.checkOrReject("write_file",
                "{\"path\":\"" + outside.resolve("a.txt").toString().replace("\\", "\\\\") + "\"}",
                context);

        assertThat(rejection).contains("Sandbox violation");
    }

    @Test
    void danger_full_access_allows_anywhere(@TempDir Path root) throws Exception {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.DANGER_FULL_ACCESS);
        Path outside = Files.createTempDirectory("babiq-danger-");
        Map<String, Object> context = Map.of(BaBiQSandboxInterceptor.CONTEXT_CWD, root.toString());

        String rejection = interceptor.checkOrReject("write_file",
                "{\"path\":\"" + outside.resolve("a.txt").toString().replace("\\", "\\\\") + "\"}",
                context);

        assertThat(rejection).isNull();
    }

    private BaBiQSandboxInterceptor newInterceptor(SandboxMode mode) {
        AgentLoopProperties properties = new AgentLoopProperties(
                20,
                ApprovalPolicy.ON_REQUEST,
                mode,
                List.of(),
                new AgentLoopProperties.Tools(new AgentLoopProperties.Output(4000)));
        return new BaBiQSandboxInterceptor(properties);
    }
}
