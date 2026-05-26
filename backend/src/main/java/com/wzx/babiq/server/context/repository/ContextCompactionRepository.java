package com.wzx.babiq.server.context.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 短期上下文压缩审计仓库。
 *
 * <p>只负责保存审计记录；当前窗口是否安装摘要由 ContextWindowRepository 负责。</p>
 */
public interface ContextCompactionRepository {

    /**
     * 保存一次压缩尝试。
     *
     * @param record 压缩审计记录
     * @return 保存后的记录
     */
    ContextCompactionRecord save(ContextCompactionRecord record);

    /**
     * 统计某个会话的压缩尝试次数。
     *
     * @param threadId 会话 id
     * @return 压缩尝试次数
     */
    long countByThreadId(String threadId);

    /**
     * 查询某个会话最近一次压缩尝试。
     *
     * @param threadId 会话 id
     * @return 最近压缩记录
     */
    Optional<ContextCompactionRecord> findLatestByThreadId(String threadId);

    /**
     * 查询需要启动恢复扫描的压缩记录。
     *
     * <p>恢复服务只关心两类记录：已经开始但没有完成的记录，以及声称 SUCCESS 但需要核对
     * active window 是否真的安装了 summary 的记录。</p>
     *
     * @param since 只扫描该时间之后的记录，避免历史数据无限膨胀
     * @return 候选恢复记录
     */
    List<ContextCompactionRecord> findRecoverableSince(Instant since);

    /**
     * 把压缩记录更新为恢复后的终态。
     *
     * @param compactionId 压缩记录 id
     * @param status 新状态，例如 ORPHANED 或 INTERRUPTED
     * @param errorMessage 恢复原因说明
     * @param completedAt 恢复完成时间；为空时由仓库使用当前时间
     */
    void updateStatus(String compactionId, String status, String errorMessage, Instant completedAt);
}
