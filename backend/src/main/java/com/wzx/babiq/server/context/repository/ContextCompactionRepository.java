package com.wzx.babiq.server.context.repository;

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
}
