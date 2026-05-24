package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.TurnSummaryEntity;

/**
 * `bq_turn_summaries` 的 MyBatis-Plus Mapper。
 *
 * <p>该 mapper 为 turn 结束后的运行反馈和 P2-5 本地观测提供基础 CRUD 能力。</p>
 */
public interface TurnSummaryMapper extends BaseMapper<TurnSummaryEntity> {
}
