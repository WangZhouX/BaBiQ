package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.MemoryReferenceEntity;

/**
 * `bq_memory_references` 的 MyBatis-Plus Mapper。
 *
 * <p>read path 引用写入由 LongTermMemoryReadService 控制，避免 UI 或模型直接制造审计记录；
 * Mapper 只作为标准持久化映射补齐。</p>
 */
public interface MemoryReferenceMapper extends BaseMapper<MemoryReferenceEntity> {
}
