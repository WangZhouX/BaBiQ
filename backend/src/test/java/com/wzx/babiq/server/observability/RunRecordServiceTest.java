package com.wzx.babiq.server.observability;

import com.wzx.babiq.server.api.dto.RunTurnDetailResult;
import com.wzx.babiq.server.api.dto.RunTurnListResult;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.conversation.repository.TurnSummaryRecord;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-4 运行记录查询测试。
 *
 * <p>run/turns/list 和 run/turn/get 背后的服务必须把 turn 快照、协议 item、summary、
 * 审批和工具调用聚合到一起。桌面端右侧运行详情面板依赖这份聚合结果展示历史轨迹。</p>
 */
@SpringBootTest
class RunRecordServiceTest {

    /** 每次测试运行使用独立数据库。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "run-record-" + UUID.randomUUID() + ".db").toAbsolutePath();

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
    private RunRecordService runRecordService;

    @Test
    @DisplayName("运行记录服务能列出 turn，并读取单个 turn 的 item、summary 和工具调用")
    void runRecordService_should_list_and_load_turn_detail() {
        Instant now = Instant.now();
        conversationRepository.createThread("thr_record", "运行记录", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now);
        turnPersistenceService.saveTurn(TurnRecord.started(
                "turn_record", "thr_record", "COMPLETED", "总结项目", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now));
        turnPersistenceService.updateTurnStatus("turn_record", "COMPLETED", null);
        conversationRepository.saveItem(ItemRecord.of(
                "it_user", "thr_record", "turn_record", "userMessage", 1,
                "{\"id\":\"it_user\",\"type\":\"userMessage\",\"text\":\"总结项目\"}", "completed", now));
        conversationRepository.saveTurnSummary(TurnSummaryRecord.of(
                "turn_record", 12, 8, 20, 3000, 1, now));
        toolCallPersistenceService.recordStarted("call_1", "thr_record", "turn_record",
                "read_file", "{\"path\":\"README.md\"}",
                "explorer", "babiq_agent", "dlg_1", now);
        toolCallPersistenceService.recordFinished("call_1", "completed", "ok", null, now.plusMillis(20));

        RunTurnListResult list = runRecordService.listTurns("thr_record", 10, null);
        RunTurnDetailResult detail = runRecordService.getTurn("turn_record");

        assertThat(list.turns()).extracting("turnId").containsExactly("turn_record");
        assertThat(detail.turn().turnId()).isEqualTo("turn_record");
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.summary().get("type").asText()).isEqualTo("turnSummary");
        assertThat(detail.summary().get("totalTokens").asLong()).isEqualTo(20L);
        assertThat(detail.summary().has("estimatedCostUsd")).isFalse();
        assertThat(detail.toolCalls()).extracting("toolName").containsExactly("read_file");
        assertThat(detail.toolCalls()).extracting("agentName").containsExactly("explorer");
        assertThat(detail.toolCalls()).extracting("parentAgentName").containsExactly("babiq_agent");
        assertThat(detail.toolCalls()).extracting("delegationId").containsExactly("dlg_1");
    }
}
