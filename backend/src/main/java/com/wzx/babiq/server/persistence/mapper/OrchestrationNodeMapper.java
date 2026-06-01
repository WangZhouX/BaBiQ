package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.OrchestrationNodeEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * P6-2 流程节点表 Mapper。
 *
 * <p>节点聚合状态通过该 Mapper 落库，工具级明细继续由 ToolCallMapper 负责。</p>
 */
@Mapper
public interface OrchestrationNodeMapper extends BaseMapper<OrchestrationNodeEntity> {
}
