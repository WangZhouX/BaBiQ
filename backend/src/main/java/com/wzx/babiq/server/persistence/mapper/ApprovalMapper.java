package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ApprovalEntity;

/**
 * `bq_approvals` 的 MyBatis-Plus Mapper。
 *
 * <p>P2-1 先提供表访问入口，P2-3 会把审批策略、Always 权限和 UI 决策写入该表。</p>
 */
public interface ApprovalMapper extends BaseMapper<ApprovalEntity> {
}
