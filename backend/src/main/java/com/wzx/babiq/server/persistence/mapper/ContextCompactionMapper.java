package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ContextCompactionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * `bq_context_compactions` 单表 mapper。
 *
 * <p>只由 SQLiteContextCompactionRepository 调用，运行层不直接依赖。</p>
 */
@Mapper
public interface ContextCompactionMapper extends BaseMapper<ContextCompactionEntity> {
}
