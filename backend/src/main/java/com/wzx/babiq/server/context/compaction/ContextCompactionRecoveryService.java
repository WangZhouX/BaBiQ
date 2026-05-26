package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.repository.ContextCompactionRecord;
import com.wzx.babiq.server.context.repository.ContextCompactionRepository;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 短期压缩启动恢复服务。
 *
 * <p>BaBiQ 以 SQLite 审计表作为事实源。服务重启时，本服务扫描最近的压缩记录，
 * 把“开始但未完成”的记录收束为 INTERRUPTED，把“声称成功但 active window 没有指向摘要”的记录收束为
 * ORPHANED。它不自动补装窗口，避免启动恢复覆盖用户已经产生的新窗口。</p>
 */
@Service
public class ContextCompactionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactionRecoveryService.class);
    private static final Duration RECOVERY_LOOKBACK = Duration.ofDays(30);

    /** 压缩审计仓库，用于读取和更新恢复候选记录。 */
    private final ContextCompactionRepository compactionRepository;
    /** 窗口仓库，用于核对 SUCCESS 记录是否真的被 active window 安装。 */
    private final ContextWindowRepository windowRepository;
    /** 最近一次恢复报告，后续状态接口可以直接读取，不需要重复扫描。 */
    private final AtomicReference<CompactionRecoveryReport> lastReport =
            new AtomicReference<>(CompactionRecoveryReport.empty());

    /**
     * 创建压缩恢复服务。
     *
     * @param compactionRepository 压缩审计仓库
     * @param windowRepository 上下文窗口仓库
     */
    public ContextCompactionRecoveryService(ContextCompactionRepository compactionRepository,
                                            ContextWindowRepository windowRepository) {
        this.compactionRepository = compactionRepository;
        this.windowRepository = windowRepository;
    }

    /**
     * 执行一次启动恢复扫描。
     *
     * @return 本次恢复报告
     */
    public CompactionRecoveryReport scan() {
        if (compactionRepository == null) {
            return CompactionRecoveryReport.empty();
        }
        Instant since = Instant.now().minus(RECOVERY_LOOKBACK);
        List<ContextCompactionRecord> records = compactionRepository.findRecoverableSince(since);
        int interrupted = 0;
        int orphaned = 0;
        int installed = 0;
        for (ContextCompactionRecord record : records) {
            if (record.startedAt() != null && record.completedAt() == null) {
                compactionRepository.updateStatus(record.compactionId(), "INTERRUPTED",
                        "服务重启时压缩流程尚未完成", null);
                interrupted++;
            } else if ("SUCCESS".equals(record.status()) && record.summaryId() != null) {
                if (isInstalled(record)) {
                    installed++;
                } else {
                    compactionRepository.updateStatus(record.compactionId(), "ORPHANED",
                            "压缩摘要未被当前窗口安装", record.completedAt());
                    orphaned++;
                }
            }
        }
        CompactionRecoveryReport report = new CompactionRecoveryReport(
                Instant.now(), records.size(), interrupted, orphaned, installed);
        lastReport.set(report);
        log.info("短期上下文压缩恢复扫描完成: scanned={}, interrupted={}, orphaned={}, installed={}",
                report.scannedCompactions(), report.interruptedCompactions(),
                report.orphanedCompactions(), report.installedCompactions());
        return report;
    }

    /**
     * 读取最近一次启动恢复报告。
     *
     * @return 最近一次报告；启动后未扫描时为空报告
     */
    public CompactionRecoveryReport lastReport() {
        return lastReport.get();
    }

    private boolean isInstalled(ContextCompactionRecord record) {
        if (windowRepository == null) {
            return false;
        }
        return windowRepository.findByThreadId(record.threadId())
                .map(window -> record.summaryId().equals(window.activeSummaryId()))
                .orElse(false);
    }
}
