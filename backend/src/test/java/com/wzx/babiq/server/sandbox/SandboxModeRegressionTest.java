package com.wzx.babiq.server.sandbox;

import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-3a 三档沙箱模式回归测试。
 *
 * <p>该类对应 M3a Done Criteria 中的 SandboxModeRegressionTest：它不重复验证工具
 * IO 本身，只锁住 D31 沙箱在 ToolInterceptor 层的模式分流和路径逃逸防护。</p>
 */
class SandboxModeRegressionTest {

    @Test
    void read_only_rejects_write_file_before_tool_runs(@TempDir Path workspace) {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.READ_ONLY);
        Map<String, Object> context = context(workspace);

        String rejection = interceptor.checkOrReject("write_file", args(workspace.resolve("a.txt")), context);

        assertThat(rejection).contains("read-only", "write_file");
    }

    @Test
    void workspace_write_allows_write_inside_cwd(@TempDir Path workspace) {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.WORKSPACE_WRITE);
        Map<String, Object> context = context(workspace);

        String rejection = interceptor.checkOrReject("write_file", args(workspace.resolve("a.txt")), context);

        assertThat(rejection).isNull();
    }

    @Test
    void workspace_write_resolves_relative_write_path_against_cwd(@TempDir Path workspace) {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.WORKSPACE_WRITE);
        Map<String, Object> context = context(workspace);

        String rejection = interceptor.checkOrReject("write_file", "{\"path\":\"index.html\"}", context);

        assertThat(rejection).isNull();
    }

    @Test
    void workspace_write_rejects_write_outside_cwd(@TempDir Path workspace) throws Exception {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.WORKSPACE_WRITE);
        Path outside = Files.createTempDirectory("babiq-outside-");
        Map<String, Object> context = context(workspace);

        String rejection = interceptor.checkOrReject("write_file", args(outside.resolve("a.txt")), context);

        assertThat(rejection).contains("Sandbox violation");
    }

    @Test
    void danger_full_access_allows_outside_cwd(@TempDir Path workspace) throws Exception {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.DANGER_FULL_ACCESS);
        Path outside = Files.createTempDirectory("babiq-danger-");
        Map<String, Object> context = context(workspace);

        String rejection = interceptor.checkOrReject("write_file", args(outside.resolve("a.txt")), context);

        assertThat(rejection).isNull();
    }

    @Test
    void workspace_write_rejects_symlink_or_traversal_escape(@TempDir Path workspace) throws Exception {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.WORKSPACE_WRITE);
        Path outside = Files.createTempDirectory(workspace.getParent(), "babiq-escape-");
        Path candidate = escapeCandidate(workspace, outside);

        String rejection = interceptor.checkOrReject("write_file", args(candidate.resolve("a.txt")), context(workspace));

        assertThat(rejection).contains("Sandbox violation");
    }

    private Path escapeCandidate(Path workspace, Path outside) throws IOException {
        Path link = workspace.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
            return link;
        } catch (IOException | UnsupportedOperationException exception) {
            return workspace.resolve("..").resolve(outside.getFileName()).normalize();
        }
    }

    private BaBiQSandboxInterceptor newInterceptor(SandboxMode mode) {
        AgentLoopProperties properties = new AgentLoopProperties(
                20,
                ApprovalPolicy.ON_REQUEST,
                mode,
                List.of(),
                new AgentLoopProperties.Tools(new AgentLoopProperties.Output(4000)));
        return new BaBiQSandboxInterceptor(properties, new ConversationService());
    }

    private Map<String, Object> context(Path workspace) {
        return Map.of(BaBiQSandboxInterceptor.CONTEXT_CWD, workspace.toString());
    }

    private String args(Path path) {
        return "{\"path\":\"" + path.toString().replace("\\", "\\\\") + "\"}";
    }
}
