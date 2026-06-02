package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.WorkUnitEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * P6-4 工作容器表 Mapper。
 *
 * <p>只提供 MyBatis-Plus 基础 CRUD；复用、软删除和目标状态语义集中在 repository adapter 中。</p>
 */
@Mapper
public interface WorkUnitMapper extends BaseMapper<WorkUnitEntity> {
}
