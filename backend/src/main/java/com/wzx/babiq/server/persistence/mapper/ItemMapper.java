package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ItemEntity;

/**
 * `bq_items` 的 MyBatis-Plus Mapper。
 *
 * <p>item 是聊天历史恢复的主要数据源；Mapper 保持轻量，排序和幂等写入由 repository 处理。</p>
 */
public interface ItemMapper extends BaseMapper<ItemEntity> {
}
