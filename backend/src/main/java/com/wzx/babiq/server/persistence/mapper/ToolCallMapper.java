package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ToolCallEntity;

/**
 * `bq_tool_calls` 的 MyBatis-Plus Mapper。
 *
 * <p>Mapper 只提供基础 CRUD；工具状态转换、结果截断和排序规则由 ToolCallPersistenceService 封装。</p>
 */
public interface ToolCallMapper extends BaseMapper<ToolCallEntity> {
}
