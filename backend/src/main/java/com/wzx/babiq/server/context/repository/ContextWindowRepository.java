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

    /**
     * 通过窗口序号做乐观安装。
     *
     * <p>压缩成功后只能在“当前窗口仍是压缩前读到的 ordinal”时安装摘要。这样即使未来同一个
     * thread 出现并发 turn，也不会让较旧的压缩结果覆盖较新的 active window。</p>
     *
     * @param threadId 会话 id
     * @param expectedOrdinal 调用方读到的旧窗口序号
     * @param nextRecord 要安装的新窗口状态
     * @return true 表示安装成功；false 表示窗口已被其他流程抢先更新
     */
    boolean compareAndSwapOrdinal(String threadId, int expectedOrdinal, ContextWindowRecord nextRecord);
}
