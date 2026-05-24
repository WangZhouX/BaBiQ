package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ApprovalRuleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * `bq_approval_rules` 的 MyBatis-Plus Mapper。
 *
 * <p>业务层必须通过 ApprovalRuleService 使用该 mapper，确保 always 规则始终绑定参数指纹。</p>
 */
@Mapper
public interface ApprovalRuleMapper extends BaseMapper<ApprovalRuleEntity> {
}
