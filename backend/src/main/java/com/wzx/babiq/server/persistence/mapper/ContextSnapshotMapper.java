package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ContextSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * `bq_context_snapshots` 单表 mapper。
 *
 * <p>只负责 MyBatis-Plus 基础 CRUD，领域查询语义集中在 repository adapter 内。</p>
 */
@Mapper
public interface ContextSnapshotMapper extends BaseMapper<ContextSnapshotEntity> {
}
