package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.observability.TurnObservationContext;
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
                ignored -> ToolCallResponse.of("read_file", "call_tool", "README"));

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
}
