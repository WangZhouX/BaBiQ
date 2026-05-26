package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.MemoryArtifactEntity;

/**
 * `bq_memory_artifacts` 的 MyBatis-Plus Mapper。
 *
 * <p>artifact 的版本、hash 和镜像写入仍由 MemoryArtifactMirror 与 repository adapter 协同维护；
 * Mapper 保持表结构在 MyBatis-Plus 层可见。</p>
 */
public interface MemoryArtifactMapper extends BaseMapper<MemoryArtifactEntity> {
}
