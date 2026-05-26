package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.MemoryJobEntity;

/**
 * `bq_memory_jobs` 的 MyBatis-Plus Mapper。
 *
 * <p>当前长期记忆流水线的复杂领取和归并 SQL 由 SQLite repository adapter 明确控制；
 * Mapper 保留基础 CRUD 能力，方便后续管理界面或诊断命令复用同一张表。</p>
 */
public interface MemoryJobMapper extends BaseMapper<MemoryJobEntity> {
}
