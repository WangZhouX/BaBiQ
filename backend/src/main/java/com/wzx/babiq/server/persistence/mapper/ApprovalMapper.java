package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ApprovalEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * `bq_approvals` 的 MyBatis-Plus Mapper。
 *
 * <p>P2-1 先提供表访问入口，P2-3 会把审批策略、Always 权限和 UI 决策写入该表。</p>
 */
public interface ApprovalMapper extends BaseMapper<ApprovalEntity> {

    @Select("""
            SELECT a.*
            FROM bq_approvals a
            JOIN bq_turns t ON t.turn_id = a.turn_id AND t.thread_id = a.thread_id
            WHERE a.turn_id = #{turnId}
              AND ((#{scoped} = 0 AND t.desktop_instance_id IS NULL)
                OR (#{scoped} = 1
                  AND t.desktop_instance_id = #{desktopInstanceId}
                  AND t.desktop_session_id = #{desktopSessionId}
                  AND t.auth_session_id = #{authSessionId}
                  AND t.identity_epoch = #{identityEpoch}
                  AND t.user_id = #{userId}
                  AND t.tenant_id = #{tenantId}
                  AND t.platform_id = #{platformId}))
            ORDER BY a.created_at ASC
            """)
    List<ApprovalEntity> selectAuthorizedByTurnId(
            @Param("turnId") String turnId,
            @Param("scoped") int scoped,
            @Param("desktopInstanceId") String desktopInstanceId,
            @Param("desktopSessionId") String desktopSessionId,
            @Param("authSessionId") String authSessionId,
            @Param("identityEpoch") Long identityEpoch,
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("platformId") String platformId);
}
