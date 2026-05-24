package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ThreadEntity;

/**
 * `bq_threads` 的 MyBatis-Plus Mapper。
 *
 * <p>Mapper 只负责单表 CRUD 能力，业务语义统一放在 repository/service 里，避免 controller 或
 * JSON-RPC handler 直接依赖数据库细节。</p>
 */
public interface ThreadMapper extends BaseMapper<ThreadEntity> {
}
