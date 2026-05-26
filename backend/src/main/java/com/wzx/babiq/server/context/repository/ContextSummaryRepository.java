package com.wzx.babiq.server.context.repository;

import java.util.Optional;

/**
 * 短期上下文摘要仓库接口。
 *
 * <p>运行时只依赖领域接口，SQLite/MyBatis-Plus 细节留在 persistence 包内。</p>
 */
public interface ContextSummaryRepository {

    /**
     * 保存一条新摘要。
     *
     * @param record 摘要记录
     * @return 保存后的记录
     */
    ContextSummaryRecord save(ContextSummaryRecord record);

    /**
     * 按摘要 id 查询。
     *
     * @param summaryId 摘要 id
     * @return 找到时返回摘要
     */
    Optional<ContextSummaryRecord> findBySummaryId(String summaryId);
}
