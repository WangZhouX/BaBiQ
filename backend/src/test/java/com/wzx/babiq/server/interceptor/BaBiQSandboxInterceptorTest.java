package com.wzx.babiq.server.interceptor;

import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.FileChangeItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * BaBiQSandboxInterceptor 单元测试。
 *
 * <p>覆盖 D31 沙箱三档与 D24 读写工具分流，确保工具保持纯 IO 时，写类风险仍在
 * ToolInterceptor 层被统一拦住。</p>
 */
class BaBiQSandboxInterceptorTest {

    private final List<ThreadItem> emitted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        emitted.clear();
    }

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
    void context_sandbox_mode_overrides_static_properties(@TempDir Path root) {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.WORKSPACE_WRITE);
        Map<String, Object> context = Map.of(
                BaBiQSandboxInterceptor.CONTEXT_CWD, root.toString(),
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.READ_ONLY.name());

        String rejection = interceptor.checkOrReject("write_file",
                "{\"path\":\"" + root.resolve("a.txt").toString().replace("\\", "\\\\") + "\"}",
                context);

        assertThat(rejection)
                .as("同一个后端进程里，设置页切换后的 turn 级沙箱模式必须覆盖 yml 默认值")
                .contains("read-only");
    }

    @Test
    void read_only_write_emits_file_change_denied(@TempDir Path root) throws Exception {
        BaBiQSandboxInterceptor interceptor = newInterceptor(SandboxMode.READ_ONLY);
        ItemEmitter emitter = capturingEmitter();
        Map<String, Object> context = Map.of(
                BaBiQSandboxInterceptor.CONTEXT_CWD, root.toString(),
                BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter);
        String arguments = "{\"path\":\"" + root.resolve("a.txt").toString().replace("\\", "\\\\") + "\"}";
        var request = new com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest(
                "write_file", arguments, "call_1", context);

        var response = interceptor.interceptToolCall(request, ignored -> {
            throw new AssertionError("沙箱拒绝时不应继续调用真实工具");
        });

        assertThat(response.toToolResponse().id()).isEqualTo("call_1");
        assertThat(response.toToolResponse().name()).isEqualTo("write_file");
        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0)).isInstanceOf(FileChangeItem.class);
        FileChangeItem item = (FileChangeItem) emitted.get(0);
        assertThat(item.status()).isEqualTo("denied");
        assertThat(item.path()).endsWith("a.txt");
        assertThat(item.contentPreview()).contains("read-only");
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
        return new BaBiQSandboxInterceptor(properties, new ConversationService());
    }

    private ItemEmitter capturingEmitter() throws Exception {
        ItemEmitter emitter = mock(ItemEmitter.class);
        doAnswer(invocation -> {
            emitted.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitFileChange(any(FileChangeItem.class));
        return emitter;
    }
}
