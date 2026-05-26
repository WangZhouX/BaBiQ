package com.wzx.babiq.server.context.repository;

import java.util.Optional;

/**
 * 上下文快照仓库接口。
 *
 * <p>快照是可审计运行记录的一部分，后续压缩、UI 和排障都通过该接口读取，而不是重新拼 prompt。</p>
 */
public interface ContextSnapshotRepository {

    /**
     * 保存一条模型调用前快照。
     *
     * @param record 快照记录
     */
    void save(ContextSnapshotRecord record);

    /**
     * 按快照 id 查询。
     *
     * @param snapshotId 快照 id
     * @return 快照记录
     */
    Optional<ContextSnapshotRecord> findBySnapshotId(String snapshotId);

    /**
     * 查询某个 turn 最近生成的快照。
     *
     * @param turnId turn id
     * @return 最近快照
     */
    Optional<ContextSnapshotRecord> findLatestByTurnId(String turnId);

    /**
     * 查询某个 thread 最近生成的快照。
     *
     * @param threadId thread id
     * @return 最近快照
     */
    Optional<ContextSnapshotRecord> findLatestByThreadId(String threadId);

    /**
     * 回填供应商返回的真实 prompt token。
     *
     * @param snapshotId 快照 id
     * @param actualPromptTokens 真实 prompt token
     */
    void updateActualPromptTokens(String snapshotId, Long actualPromptTokens);
}
