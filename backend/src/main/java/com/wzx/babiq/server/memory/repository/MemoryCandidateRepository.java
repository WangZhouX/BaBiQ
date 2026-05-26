package com.wzx.babiq.server.memory.repository;

import java.time.Instant;
import java.util.List;

/**
 * 长期记忆候选仓库端口。
 */
public interface MemoryCandidateRepository {

    /** 统计尚未归并的 CLEAN 候选。 */
    default long countUnmergedCleanCandidates() {
        return 0;
    }

    /** 按 Codex 风格排序选择 Phase2 输入。 */
    default List<MemoryCandidateRecord> selectForPhase2(int limit) {
        return List.of();
    }

    /** 保存候选。 */
    default void save(MemoryCandidateRecord record) {
    }

    /** 标记候选已被 Phase2 选中。 */
    default void markSelected(List<String> candidateIds, Instant selectedAt) {
    }
}
