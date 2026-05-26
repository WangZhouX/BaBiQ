package com.wzx.babiq.server.context.repository;

import java.util.Optional;

/**
 * 上下文窗口状态仓库接口。
 *
 * <p>Agent/runtime 层只依赖这个接口，不直接依赖 MyBatis mapper，保持 P2 以来的分层边界。</p>
 */
public interface ContextWindowRepository {

    /**
     * 按会话 id 查找当前窗口状态。
     *
     * @param threadId 会话 id
     * @return 当前窗口状态；从未生成过上下文快照时为空
     */
    Optional<ContextWindowRecord> findByThreadId(String threadId);

    /**
     * 创建或更新 thread 的当前窗口状态。
     *
     * @param record 要写入的窗口状态
     * @return 写入后的窗口状态
     */
    ContextWindowRecord upsert(ContextWindowRecord record);
}
