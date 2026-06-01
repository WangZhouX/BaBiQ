package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.OrchestrationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * P6-2 流程运行表 Mapper。
 *
 * <p>只提供 MyBatis-Plus 基础 CRUD，业务语义集中在 repository adapter 中。</p>
 */
@Mapper
public interface OrchestrationMapper extends BaseMapper<OrchestrationEntity> {
}
