package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.MemoryCandidateEntity;

/**
 * `bq_memory_candidates` 的 MyBatis-Plus Mapper。
 *
 * <p>候选选择排序、污染过滤和 selected_for_phase2 标记必须经过 memory repository；
 * Mapper 只提供与仓库其他实体一致的基础映射入口。</p>
 */
public interface MemoryCandidateMapper extends BaseMapper<MemoryCandidateEntity> {
}
