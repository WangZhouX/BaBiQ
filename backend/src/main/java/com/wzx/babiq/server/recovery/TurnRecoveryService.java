package com.wzx.babiq.server.recovery;

import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.service.ApprovalPersistenceService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P2-4 启动恢复服务。
 *
 * <p>BaBiQ P2 只做 SQLite 级别的历史恢复，不恢复 Spring AI Alibaba Graph 的内存检查点。
 * 因此进程重启后，数据库里遗留的 RUNNING/SENDING/WAITING_APPROVAL 必须被改成可解释终态：
 * 运行中的 turn 标记为 INTERRUPTED，等待审批的 turn 标记为 EXPIRED，同时把 pending 审批过期。</p>
 */
@Service
public class TurnRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TurnRecoveryService.class);

    /** 运行中遗留状态，说明上一次进程没有正常发出 turn/completed。 */
    private static final List<String> RUNNING_STATUSES = List.of("RUNNING", "SENDING");
    /** 等待审批状态，P2 重启后无法恢复内存暂停点，只能过期。 */
    private static final List<String> WAITING_APPROVAL_STATUSES = List.of("WAITING_APPROVAL");

    /** turn 表服务，负责查询遗留状态并写入恢复结果。 */
    private final TurnPersistenceService turnPersistenceService;
    /** 审批表服务，负责把等待中的审批过期。 */
    private final ApprovalPersistenceService approvalPersistenceService;
    /** 最近一次恢复报告；run/recovery/status 会读取它。 */
    private final AtomicReference<RecoveryReport> lastReport = new AtomicReference<>(RecoveryReport.empty());

    /**
     * 创建启动恢复服务。
     *
     * @param turnPersistenceService turn 持久化服务
     * @param approvalPersistenceService 审批持久化服务
     */
    public TurnRecoveryService(
            TurnPersistenceService turnPersistenceService,
            ApprovalPersistenceService approvalPersistenceService) {
        this.turnPersistenceService = turnPersistenceService;
        this.approvalPersistenceService = approvalPersistenceService;
    }

    /**
     * 扫描并收口所有上次进程遗留的非终态 turn。
     *
     * @return 本次恢复报告
     */
    @Transactional
    public RecoveryReport recoverAbandonedState() {
        Instant recoveredAt = Instant.now();
        List<TurnEntity> runningTurns = turnPersistenceService.findByStatuses(RUNNING_STATUSES);
        List<TurnEntity> waitingTurns = turnPersistenceService.findByStatuses(WAITING_APPROVAL_STATUSES);

        for (TurnEntity turn : runningTurns) {
            turnPersistenceService.markRecovered(
                    turn.getTurnId(), "INTERRUPTED", "server_restarted_while_running", recoveredAt);
        }
        for (TurnEntity turn : waitingTurns) {
            turnPersistenceService.markRecovered(
                    turn.getTurnId(), "EXPIRED", "server_restarted_while_waiting_approval", recoveredAt);
        }
        int expiredApprovals = approvalPersistenceService.expirePendingByTurnIds(
                waitingTurns.stream().map(TurnEntity::getTurnId).toList(), recoveredAt);

        RecoveryReport report = new RecoveryReport(
                recoveredAt, runningTurns.size(), waitingTurns.size(), expiredApprovals);
        lastReport.set(report);
        log.info("P2-4 启动恢复完成: interruptedTurns={}, expiredTurns={}, expiredApprovals={}",
                report.interruptedTurns(), report.expiredTurns(), report.expiredApprovals());
        return report;
    }

    /**
     * 返回最近一次恢复报告。
     *
     * @return 最近恢复报告；如果尚未运行过则返回空报告
     */
    public RecoveryReport lastReport() {
        return lastReport.get();
    }
}
