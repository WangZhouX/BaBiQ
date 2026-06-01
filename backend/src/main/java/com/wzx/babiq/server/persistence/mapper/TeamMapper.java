package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.TeamEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * P6-3 团队协作整体运行表 Mapper。
 *
 * <p>只提供 MyBatis-Plus 基础 CRUD，业务语义集中在 repository adapter 中。</p>
 */
@Mapper
public interface TeamMapper extends BaseMapper<TeamEntity> {
}
