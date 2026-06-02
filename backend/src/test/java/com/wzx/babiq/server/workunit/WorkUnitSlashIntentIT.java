package com.wzx.babiq.server.workunit;

import com.alibaba.cloud.ai.graph.agent.Agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.flow.BabiqFlowSpec;
import com.wzx.babiq.server.agent.flow.FlowApprovalService;
import com.wzx.babiq.server.agent.flow.FlowOrchestrationService;
import com.wzx.babiq.server.agent.flow.OrchestrationRepository;
import com.wzx.babiq.server.api.dto.WorkUnitInfo;
import com.wzx.babiq.server.api.dto.WorkUnitListResult;
import com.wzx.babiq.server.api.dto.WorkUnitRemoveResult;
import com.wzx.babiq.server.api.method.TurnStartHandler;
import com.wzx.babiq.server.api.method.WorkUnitListHandler;
import com.wzx.babiq.server.api.method.WorkUnitRemoveHandler;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.tool.impl.FlowOrchestrationTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P6-4 slash 工作容器端到端集成测试。
 *
 * <p>该测试覆盖用户显式 slash 创建容器、详情页/主 Agent 显式启动、flow 回写目标、
 * 列表可见性和手动移除。它不调用真实模型，只验证 BaBiQ 自身协议、SQLite 和
 * WorkUnit 生命周期边界。</p>
 */
@SpringBootTest
class WorkUnitSlashIntentIT {

    private static final Path TEST_DB = Path.of("target", "test-db",
            "workunit-slash-" + UUID.randomUUID() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private TurnStartHandler turnStartHandler;
    @Autowired
    private WorkUnitService workUnitService;
    @Autowired
    private WorkUnitListHandler listHandler;
    @Autowired
    private WorkUnitRemoveHandler removeHandler;

    @Test
    void slash_intent_should_create_start_complete_list_and_remove_work_unit() throws Exception {
        Thread thread = conversationService.createThread("H:/aaa");
        List<String> payloads = new ArrayList<>();
        WebSocketSession session = recordingSession(payloads);

        turnStartHandler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of("type", "text", "text", "/编排 登录页重构: 拆分登录页改造流程"),
                        "executionIntent", Map.of(
                                "type", "create_work_unit",
                                "kind", "orchestration",
                                "name", "登录页重构",
                                "goal", "拆分登录页改造流程"))),
                session);

        WorkUnitListResult createdList = list(thread.id());
        assertThat(createdList.workUnits()).hasSize(1);
        WorkUnitInfo created = createdList.workUnits().getFirst();
        assertThat(created.kind()).isEqualTo("orchestration");
        assertThat(created.name()).isEqualTo("登录页重构");
        assertThat(created.status()).isEqualTo("waiting_config");
        assertThat(created.goals()).singleElement().satisfies(goal -> {
            assertThat(goal.goalText()).isEqualTo("拆分登录页改造流程");
            assertThat(goal.status()).isEqualTo("pending");
            assertThat(goal.runRefId()).isNull();
        });
        assertThat(payloads).anyMatch(payload -> payload.contains("\"method\":\"item/added\"")
                && payload.contains("\"type\":\"workUnit\""));
        assertThat(payloads).anyMatch(payload -> payload.contains("\"method\":\"turn/completed\""));

        String output = runFlowFor(created.currentGoalId(), thread.id());

        WorkUnitInfo completed = list(thread.id()).workUnits().getFirst();
        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.goals()).singleElement().satisfies(goal -> {
            assertThat(goal.status()).isEqualTo("completed");
            assertThat(goal.runRefType()).isEqualTo("orchestration");
            assertThat(goal.runRefId()).startsWith("orch_");
            assertThat(goal.summary()).isEqualTo(output);
        });

        WorkUnitRemoveResult removed = remove(completed.workUnitId());
        assertThat(removed.removed()).isTrue();
        assertThat(removed.status()).isEqualTo("removed");
        assertThat(list(thread.id()).workUnits()).isEmpty();
    }

    private String runFlowFor(String goalId, String threadId) throws Exception {
        FlowOrchestrationService flowService = mock(FlowOrchestrationService.class);
        OrchestrationRepository repository = mock(OrchestrationRepository.class);
        Agent agent = mock(Agent.class);
        when(flowService.buildOfficialFlowAgent(any(BabiqFlowSpec.class), any(ToolContext.class), isNull()))
                .thenReturn(agent);
        when(agent.invoke(anyString())).thenReturn(Optional.empty());
        FlowOrchestrationTool tool = new FlowOrchestrationTool(
                flowService,
                repository,
                new FlowApprovalService(),
                workUnitService);
        return tool.orchestrateFlow("拆分登录页改造流程", "sequential", List.of(), toolContext(goalId, threadId));
    }

    private ToolContext toolContext(String goalId, String threadId) {
        return new ToolContext(Map.of(
                WorkUnitContextKeys.GOAL_ID, goalId,
                BaBiQSandboxInterceptor.CONTEXT_CWD, "H:/aaa",
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.READ_ONLY.name(),
                "babiq.threadId", threadId));
    }

    private WorkUnitListResult list(String threadId) {
        return (WorkUnitListResult) listHandler.handle(
                objectMapper.valueToTree(Map.of("threadId", threadId)),
                null);
    }

    private WorkUnitRemoveResult remove(String workUnitId) {
        return (WorkUnitRemoveResult) removeHandler.handle(
                objectMapper.valueToTree(Map.of("workUnitId", workUnitId)),
                null);
    }

    private WebSocketSession recordingSession(List<String> payloads) {
        return (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(),
                new Class<?>[]{WebSocketSession.class},
                (proxy, method, args) -> {
                    if ("sendMessage".equals(method.getName())) {
                        payloads.add(((TextMessage) args[0]).getPayload());
                        return null;
                    }
                    if ("getId".equals(method.getName())) {
                        return "test-session";
                    }
                    if ("isOpen".equals(method.getName())) {
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Character.TYPE) {
            return '\0';
        }
        return 0;
    }
}
