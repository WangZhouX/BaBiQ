package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.TeamMemberEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * P6-3 团队成员聚合状态表 Mapper。
 */
@Mapper
public interface TeamMemberMapper extends BaseMapper<TeamMemberEntity> {
}
