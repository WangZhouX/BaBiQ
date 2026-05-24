package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.TurnEntity;

/**
 * `bq_turns` 的 MyBatis-Plus Mapper。
 *
 * <p>后续恢复未完成 turn、查询运行记录时都会从这里出发，但具体查询组合由 service 封装。</p>
 */
public interface TurnMapper extends BaseMapper<TurnEntity> {
}
