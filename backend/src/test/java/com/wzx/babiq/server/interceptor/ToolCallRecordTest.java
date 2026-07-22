package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.persistence.entity.ToolCallEntity;
import com.wzx.babiq.server.persistence.mapper.ToolCallMapper;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具调用落库测试。
 *
 * <p>P2-4 不只统计工具次数，还要保存每次工具调用的入参、状态和错误，方便后续运行详情面板
 * 回放“Agent 为什么做了这个动作”。</p>
 */
@SpringBootTest
class ToolCallRecordTest {

    /** 工具调用测试使用独立 SQLite 文件。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "tool-call-record-" + UUID.randomUUID() + ".db").toAbsolutePath();

    /** 覆盖默认数据库路径。 */
    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private TurnPersistenceService turnPersistenceService;
    @Autowired
    private ToolCallPersistenceService toolCallPersistenceService;
    @Autowired
    private ToolCallMapper toolCallMapper;

    @Test
    @DisplayName("ToolObservationInterceptor 会把工具调用开始和完成状态写入 bq_tool_calls")
    void interceptor_should_persist_tool_call_record() {
        Instant now = Instant.now();
        conversationRepository.createThread("thr_tool", "工具记录", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now);
        turnPersistenceService.saveTurn(TurnRecord.started(
                "turn_tool", "thr_tool", "RUNNING", "读取文件", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now));
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(
                new BaBiQMetrics(), toolCallPersistenceService);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_tool", "turn_tool", "deepseek", "deepseek-v4-pro");
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("read_file")
                .toolCallId("call_tool")
                .arguments("{\"path\":\"README.md\"}")
                .context(Map.of(TurnObservationContext.METADATA_KEY, context))
                .build();

        ToolCallResponse response = interceptor.interceptToolCall(request,
                ignored -> ToolCallResponse.of("call_tool", "read_file", "README"));

        assertThat(response.getResult()).isEqualTo("README");
        assertThat(toolCallPersistenceService.listByTurnId("turn_tool"))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.toolCallId()).isEqualTo("call_tool");
                    assertThat(record.toolName()).isEqualTo("read_file");
                    assertThat(record.status()).isEqualTo("completed");
                    assertThat(record.resultPreview()).isEqualTo("README");
                });
    }

    @Test
    @DisplayName("不同 turn 复用同一工具调用 ID 时分别完成各自记录")
    void interceptor_should_scope_reused_tool_call_id_to_turn() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String threadId = "thr_tool_scope_" + suffix;
        String firstTurnId = "turn_tool_scope_a_" + suffix;
        String secondTurnId = "turn_tool_scope_b_" + suffix;
        String toolCallId = "application_action_0";
        Instant now = Instant.now();
        conversationRepository.createThread(threadId, "工具组合身份", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now);
        turnPersistenceService.saveTurn(TurnRecord.started(
                firstTurnId, threadId, "RUNNING", "第一次调用", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now));
        turnPersistenceService.saveTurn(TurnRecord.started(
                secondTurnId, threadId, "RUNNING", "第二次调用", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now.plusSeconds(1)));
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(
                new BaBiQMetrics(), toolCallPersistenceService);

        intercept(interceptor, threadId, firstTurnId, toolCallId, "first-result");
        intercept(interceptor, threadId, secondTurnId, toolCallId, "second-result");
        toolCallPersistenceService.bindExecutionId(
                firstTurnId, toolCallId, BusinessIdentityScope.UNSCOPED, "execution-first");
        toolCallPersistenceService.bindExecutionId(
                secondTurnId, toolCallId, BusinessIdentityScope.UNSCOPED, "execution-second");

        assertThat(toolCallPersistenceService.listByTurnId(firstTurnId))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.toolCallId()).isEqualTo(toolCallId);
                    assertThat(record.status()).isEqualTo("completed");
                    assertThat(record.resultPreview()).isEqualTo("first-result");
                });
        assertThat(toolCallPersistenceService.listByTurnId(secondTurnId))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.toolCallId()).isEqualTo(toolCallId);
                    assertThat(record.status()).isEqualTo("completed");
                    assertThat(record.resultPreview()).isEqualTo("second-result");
                });
        assertThat(findToolCall(firstTurnId, toolCallId).getExecutionId()).isEqualTo("execution-first");
        assertThat(findToolCall(secondTurnId, toolCallId).getExecutionId()).isEqualTo("execution-second");
    }

    private ToolCallEntity findToolCall(String turnId, String toolCallId) {
        return toolCallMapper.selectOne(Wrappers.<ToolCallEntity>lambdaQuery()
                .eq(ToolCallEntity::getTurnId, turnId)
                .eq(ToolCallEntity::getToolCallId, toolCallId));
    }

    private static void intercept(
            ToolObservationInterceptor interceptor,
            String threadId,
            String turnId,
            String toolCallId,
            String result) {
        TurnObservationContext context = TurnObservationContext.start(
                threadId, turnId, "deepseek", "deepseek-v4-pro");
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("application_action")
                .toolCallId(toolCallId)
                .arguments("{\"actionId\":\"form.read_state\",\"input\":{}}")
                .context(Map.of(TurnObservationContext.METADATA_KEY, context))
                .build();
        interceptor.interceptToolCall(request,
                ignored -> ToolCallResponse.of(toolCallId, "application_action", result));
    }
}
