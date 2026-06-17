package com.wzx.babiq.server.agent.team;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.agent.delegation.BuiltInSubAgents;
import com.wzx.babiq.server.agent.delegation.SubAgentDelegationContext;
import com.wzx.babiq.server.agent.delegation.SubAgentRuntimeFactory;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.repository.ToolCallRecord;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * P6-3a 执行模型 spike。
 *
 * <p>本测试用真实 Spring AI Alibaba {@link AgentTool} 调用团队成员 {@link ReactAgent}，
 * 验证 Path A（BaBiQ 自驱循环逐轮调用成员 AgentTool）不会丢失父 {@link ToolContext}
 * 中的 cwd、emitter、observation 和沙箱边界；通过后 T6 锁定 Path A。</p>
 */
@SpringBootTest(properties = {
        "babiq.agent.approval-policy=NEVER",
        "babiq.agent.sandbox-mode=WORKSPACE_WRITE",
        "babiq.memory.long-term.phase1-scan-interval-millis=86400000",
        "babiq.memory.long-term.phase2-scan-interval-millis=86400000"
})
class TeamExecutionModelSpikeTest {

    /** 每次 spike 使用独立 SQLite 文件，避免污染开发库。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "team-execution-model-spike-" + UUID.randomUUID() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @MockBean
    private ChatClientFactory chatClientFactory;

    @Autowired
    private SubAgentRuntimeFactory runtimeFactory;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private TurnPersistenceService turnPersistenceService;
    @Autowired
    private ToolCallPersistenceService toolCallPersistenceService;

    @Test
    void path_a_member_agent_tool_should_keep_context_and_record_member_tool_call(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<h1>team spike</h1>");
        TeamListDirChatModel model = new TeamListDirChatModel();
        Mockito.when(chatClientFactory.resolveChatModel(isNull())).thenReturn(model);
        String threadId = "thr_team_spike";
        String turnId = "turn_team_spike";
        Instant now = Instant.now();
        conversationRepository.createThread(threadId, "团队执行模型 spike", workspace.toString(),
                "mock-provider", "mock-model", "WORKSPACE_WRITE", "NEVER", now);
        turnPersistenceService.saveTurn(TurnRecord.started(
                turnId, threadId, "RUNNING", "验证团队成员 AgentTool", workspace.toString(),
                "mock-provider", "mock-model", "WORKSPACE_WRITE", "NEVER", now));
        TurnObservationContext observation = TurnObservationContext.start(
                threadId, turnId, "mock-provider", "mock-model");
        ItemEmitter emitter = Mockito.mock(ItemEmitter.class);
        RunnableConfig parentConfig = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata(TurnObservationContext.METADATA_KEY, observation)
                .addMetadata(BaBiQSandboxInterceptor.CONTEXT_CWD, workspace.toString())
                .addMetadata(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter)
                .build();
        ToolContext parentContext = new ToolContext(Map.of(
                SubAgentRuntimeFactory.AGENT_CONFIG_KEY, parentConfig,
                TurnObservationContext.METADATA_KEY, observation,
                BaBiQSandboxInterceptor.CONTEXT_CWD, workspace.toString(),
                BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter,
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.DANGER_FULL_ACCESS.name()));
        SubAgentDelegationContext delegation = SubAgentDelegationContext.started(
                "it_team_spike",
                "dlg_team_spike",
                BuiltInSubAgents.MAIN_AGENT_NAME,
                "spike_reader",
                BabiqAgentMode.READ_ONLY_TOOL,
                emitter,
                observation);
        ToolContext childContext = SubAgentRuntimeFactory.withDelegationContext(
                parentContext,
                delegation,
                SandboxMode.READ_ONLY);
        MemorySaver saver = new MemorySaver();
        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .build();
        ReactAgent memberAgent = runtimeFactory.buildChildAgentForTeam(
                spikeMemberSpec(),
                childContext,
                "spike_reader_output",
                saver,
                compileConfig);
        ToolCallback callback = AgentTool.getFunctionToolCallback(memberAgent);

        String result = callback.call(new ObjectMapper().writeValueAsString(Map.of("input", "列出当前目录")),
                childContext);

        assertThat(result).contains("index.html");
        assertThat(model.sawListDirToolResponse()).isTrue();
        assertThat(observation.toolCalls()).isEqualTo(1);
        assertThat(childContext.getContext())
                .containsEntry(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.READ_ONLY.name())
                .containsEntry(BaBiQSandboxInterceptor.CONTEXT_CWD, workspace.toString())
                .containsEntry(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter);
        assertThat(toolCallPersistenceService.listByTurnId(turnId))
                .singleElement()
                .satisfies(record -> assertMemberToolCall(record));
    }

    private BabiqAgentSpec spikeMemberSpec() {
        return new BabiqAgentSpec(
                "spike_reader",
                "Spike Reader",
                "P6-3a Path A spike member",
                "你是团队 spike 成员，只能使用只读工具并汇报文件列表。",
                List.of("list_dir"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                BabiqAgentMode.READ_ONLY_TOOL);
    }

    private void assertMemberToolCall(ToolCallRecord record) {
        assertThat(record.toolName()).isEqualTo("list_dir");
        assertThat(record.status()).isEqualTo("completed");
        assertThat(record.agentName()).isEqualTo("spike_reader");
        assertThat(record.parentAgentName()).isEqualTo(BuiltInSubAgents.MAIN_AGENT_NAME);
        assertThat(record.delegationId()).isEqualTo("dlg_team_spike");
    }

    /**
     * 两轮模型：第一轮请求 list_dir，第二轮验证工具响应已进入成员上下文后返回摘要。
     */
    private static final class TeamListDirChatModel implements ChatModel {

        private final AtomicInteger callCounter = new AtomicInteger();
        private boolean sawListDirToolResponse;

        @Override
        public ChatResponse call(Prompt prompt) {
            int currentCall = callCounter.incrementAndGet();
            if (currentCall == 1) {
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_team_list_dir", "function", "list_dir", "{\"path\":\".\"}")))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }
            sawListDirToolResponse = hasToolResponse(prompt.getInstructions(), "call_team_list_dir", "list_dir");
            if (!sawListDirToolResponse) {
                throw new AssertionError("团队成员第二轮模型调用前没有收到 list_dir 工具响应");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "spike_reader 摘要：当前目录包含 index.html。"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        boolean sawListDirToolResponse() {
            return sawListDirToolResponse;
        }

        private boolean hasToolResponse(List<Message> messages, String toolCallId, String toolName) {
            for (Message message : messages) {
                if (message instanceof ToolResponseMessage toolResponseMessage) {
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        if (toolCallId.equals(response.id()) && toolName.equals(response.name())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }
}
