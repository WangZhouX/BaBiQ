package com.wzx.babiq.server.recovery;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.persistence.entity.ApprovalEntity;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.mapper.ApprovalMapper;
import com.wzx.babiq.server.persistence.mapper.TurnMapper;
import com.wzx.babiq.server.persistence.service.ApprovalPersistenceService;
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
 * P2-4 启动恢复语义测试。
 *
 * <p>本测试直接构造“上一次进程崩溃后遗留的数据库状态”，再要求恢复服务把非终态
 * turn 和 pending 审批收口为可解释的终态。这样桌面端重启后不会看到永远运行中的历史记录。</p>
 */
@SpringBootTest
class TurnRecoveryServiceTest {

    /** 每个测试上下文使用独立 SQLite 文件，避免恢复结果被旧数据干扰。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "turn-recovery-" + UUID.randomUUID() + ".db").toAbsolutePath();

    /** 将 Spring Boot 测试数据库指向临时文件。 */
    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private TurnPersistenceService turnPersistenceService;
    @Autowired
    private ApprovalPersistenceService approvalPersistenceService;
    @Autowired
    private TurnRecoveryService recoveryService;
    @Autowired
    private TurnMapper turnMapper;
    @Autowired
    private ApprovalMapper approvalMapper;

    @Test
    @DisplayName("启动恢复会把 RUNNING 标成 INTERRUPTED，把 WAITING_APPROVAL 标成 EXPIRED")
    void recoverAbandonedState_should_close_running_and_waiting_approval_turns() {
        Instant now = Instant.now();
        conversationRepository.createThread("thr_recover", "恢复测试", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now);
        turnPersistenceService.saveTurn(TurnRecord.started(
                "turn_running", "thr_recover", "RUNNING", "执行中", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now));
        turnPersistenceService.saveTurn(TurnRecord.started(
                "turn_waiting", "thr_recover", "WAITING_APPROVAL", "等待审批", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now));
        approvalPersistenceService.savePending(
                "appr_waiting", "thr_recover", "turn_waiting",
                "write_file", "{\"path\":\"README.md\"}", now);

        RecoveryReport report = recoveryService.recoverAbandonedState();

        assertThat(report.interruptedTurns()).isEqualTo(1);
        assertThat(report.expiredTurns()).isEqualTo(1);
        assertThat(report.expiredApprovals()).isEqualTo(1);
        assertThat(report.lastRecoveredAt()).isNotNull();
        assertRecoveredTurn("turn_running", "INTERRUPTED", "server_restarted_while_running");
        assertRecoveredTurn("turn_waiting", "EXPIRED", "server_restarted_while_waiting_approval");

        ApprovalEntity approval = approvalMapper.selectOne(Wrappers.<ApprovalEntity>lambdaQuery()
                .eq(ApprovalEntity::getApprovalId, "appr_waiting"));
        assertThat(approval.getStatus()).isEqualTo("expired");
        assertThat(approval.getResolvedAt()).isNotBlank();
    }

    private void assertRecoveredTurn(String turnId, String status, String reason) {
        TurnEntity entity = turnMapper.selectOne(Wrappers.<TurnEntity>lambdaQuery()
                .eq(TurnEntity::getTurnId, turnId));
        assertThat(entity.getStatus()).isEqualTo(status);
        assertThat(entity.getRecoveryReason()).isEqualTo(reason);
        assertThat(entity.getRecoveredAt()).isNotBlank();
        assertThat(entity.getCompletedAt()).isNotBlank();
    }
}
