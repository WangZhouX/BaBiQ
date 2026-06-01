package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.TeamMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * P6-3 团队消息时间线表 Mapper。
 */
@Mapper
public interface TeamMessageMapper extends BaseMapper<TeamMessageEntity> {
}
