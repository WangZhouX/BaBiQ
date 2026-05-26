package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ContextWindowEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * `bq_context_windows` 单表 mapper。
 *
 * <p>仅由 SQLiteContextWindowRepository 调用，Agent 运行期不直接依赖 mapper。</p>
 */
@Mapper
public interface ContextWindowMapper extends BaseMapper<ContextWindowEntity> {
}
