package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ContextSummaryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * `bq_context_summaries` 单表 mapper。
 *
 * <p>只由 SQLiteContextSummaryRepository 调用，运行层不直接依赖。</p>
 */
@Mapper
public interface ContextSummaryMapper extends BaseMapper<ContextSummaryEntity> {
}
