package com.wzx.babiq.server.agent.delegation;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.AgentDelegationItem;
import com.wzx.babiq.server.conversation.items.CommandExecutionItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ToolCallRecord;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * 子 Agent 委派端到端集成测试。
 *
 * <p>该测试使用真实 Spring 上下文、真实 {@link ExplorerSubAgentTool}、真实本地只读工具、
 * 真实拦截器和真实 SQLite 记录，只 mock 模型层。它覆盖 P6-1 最关键的行为链：
 * 委派进入 explorer、子 Agent 只读工具执行、工具归属落库、父聊天流只收到聚合 item。</p>
 */
@SpringBootTest(properties = {
        "babiq.agent.approval-policy=NEVER",
        "babiq.agent.sandbox-mode=WORKSPACE_WRITE",
        "babiq.memory.long-term.phase1-scan-interval-millis=86400000",
        "babiq.memory.long-term.phase2-scan-interval-millis=86400000"
})
class SubAgentDelegationIT {

    /** 每个测试类使用独立 SQLite 文件，避免和本地开发数据库互相锁定。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "sub-agent-delegation-" + UUID.randomUUID() + ".db").toAbsolutePath();

    /** 覆盖默认数据库路径，保证集成测试可重复执行。 */
    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @MockBean
    private ChatClientFactory chatClientFactory;

    @Autowired
    private ExplorerSubAgentTool explorerSubAgentTool;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private TurnPersistenceService turnPersistenceService;
    @Autowired
    private ToolCallPersistenceService toolCallPersistenceService;
    @Autowired
    private BaBiQSandboxInterceptor sandboxInterceptor;

    @Test
    void explorer_delegation_should_run_read_only_tool_record_ownership_and_emit_only_delegation_items(
            @TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<h1>hello</h1>");
        ExplorerListDirChatModel model = new ExplorerListDirChatModel();
        Mockito.when(chatClientFactory.resolveChatModel(isNull())).thenReturn(model);
        String threadId = "thr_sub_agent_it";
        String turnId = "turn_sub_agent_it";
        Instant now = Instant.now();
        conversationRepository.createThread(threadId, "子 Agent 委派", workspace.toString(),
                "mock-provider", "mock-model", "WORKSPACE_WRITE", "NEVER", now);
        turnPersistenceService.saveTurn(TurnRecord.started(
                turnId, threadId, "RUNNING", "让 explorer 看看目录", workspace.toString(),
                "mock-provider", "mock-model", "WORKSPACE_WRITE", "NEVER", now));
        List<ThreadItem> emittedItems = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emittedItems);
        TurnObservationContext observation = TurnObservationContext.start(
                threadId, turnId, "mock-provider", "mock-model");
        RunnableConfig parentConfig = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata(TurnObservationContext.METADATA_KEY, observation)
                .addMetadata(BaBiQSandboxInterceptor.CONTEXT_CWD, workspace.toString())
                .build();
        ToolContext parentContext = new ToolContext(Map.of(
                SubAgentRuntimeFactory.AGENT_CONFIG_KEY, parentConfig,
                TurnObservationContext.METADATA_KEY, observation,
                BaBiQSandboxInterceptor.CONTEXT_CWD, workspace.toString(),
                BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter,
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, "DANGER_FULL_ACCESS"));

        String result = explorerSubAgentTool.explore("列出当前目录并总结", parentContext);

        assertThat(result).contains("index.html");
        assertThat(model.sawListDirToolResponse()).isTrue();
        assertThat(emittedItems).noneMatch(CommandExecutionItem.class::isInstance);
        assertThat(emittedItems)
                .filteredOn(AgentDelegationItem.class::isInstance)
                .hasSizeGreaterThanOrEqualTo(2);
        AgentDelegationItem completed = emittedItems.stream()
                .filter(AgentDelegationItem.class::isInstance)
                .map(AgentDelegationItem.class::cast)
                .filter(item -> "completed".equals(item.status()))
                .findFirst()
                .orElseThrow();
        assertThat(completed.toolCallCount()).isEqualTo(1);
        assertThat(completed.summary()).contains("index.html");

        assertThat(toolCallPersistenceService.listByTurnId(turnId))
                .singleElement()
                .satisfies(record -> assertChildToolCall(record, completed.delegationId()));

        SubAgentDelegationContext delegation = SubAgentDelegationContext.started(
                "it_sandbox_assert",
                "dlg_sandbox_assert",
                BuiltInSubAgents.MAIN_AGENT_NAME,
                BuiltInSubAgents.EXPLORER_NAME,
                BabiqAgentMode.READ_ONLY_TOOL,
                emitter,
                observation);
        ToolContext childContext = SubAgentRuntimeFactory.withDelegationContext(parentContext, delegation);
        String rejection = sandboxInterceptor.checkOrReject("write_file",
                "{\"path\":\"attempt.txt\",\"content\":\"nope\"}",
                childContext.getContext());
        assertThat(rejection).contains("read-only");
        assertThat(BuiltInSubAgents.explorer().toolNames()).doesNotContain("write_file", "exec_shell", "apply_patch");
    }

    private void assertChildToolCall(ToolCallRecord record, String delegationId) {
        assertThat(record.toolName()).isEqualTo("list_dir");
        assertThat(record.status()).isEqualTo("completed");
        assertThat(record.agentName()).isEqualTo(BuiltInSubAgents.EXPLORER_NAME);
        assertThat(record.parentAgentName()).isEqualTo(BuiltInSubAgents.MAIN_AGENT_NAME);
        assertThat(record.delegationId()).isEqualTo(delegationId);
    }

    private ItemEmitter capturingEmitter(List<ThreadItem> emittedItems) throws Exception {
        ItemEmitter emitter = Mockito.mock(ItemEmitter.class);
        Mockito.doAnswer(invocation -> {
            emittedItems.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitItemAdded(any(ThreadItem.class));
        Mockito.doAnswer(invocation -> {
            emittedItems.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitItemUpdated(any(ThreadItem.class));
        Mockito.doAnswer(invocation -> {
            emittedItems.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitCommandExecution(any(ThreadItem.class));
        Mockito.doAnswer(invocation -> {
            emittedItems.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitFileChange(any(ThreadItem.class));
        return emitter;
    }

    /**
     * 用两轮模型响应模拟 explorer：先请求 list_dir，再基于工具响应返回摘要。
     */
    private static final class ExplorerListDirChatModel implements ChatModel {

        private final AtomicInteger callCounter = new AtomicInteger();
        private boolean sawListDirToolResponse;

        @Override
        public ChatResponse call(Prompt prompt) {
            int currentCall = callCounter.incrementAndGet();
            if (currentCall == 1) {
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_list_dir", "function", "list_dir", "{\"path\":\".\"}")))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }
            sawListDirToolResponse = hasToolResponse(prompt.getInstructions(), "call_list_dir", "list_dir");
            if (!sawListDirToolResponse) {
                throw new AssertionError("explorer 第二轮模型调用前没有收到 list_dir 工具响应");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "explorer 摘要：当前目录包含 index.html。"))));
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
