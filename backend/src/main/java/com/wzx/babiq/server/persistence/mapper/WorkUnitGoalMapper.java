package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.WorkUnitGoalEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * P6-4 工作容器目标表 Mapper。
 *
 * <p>目标队列由 repository adapter 负责按创建时间稳定排序；Mapper 不承载业务语义。</p>
 */
@Mapper
public interface WorkUnitGoalMapper extends BaseMapper<WorkUnitGoalEntity> {
}
