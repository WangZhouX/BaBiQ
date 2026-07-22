package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ToolCallEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * `bq_tool_calls` 的 MyBatis-Plus Mapper。
 *
 * <p>Mapper 只提供基础 CRUD；工具状态转换、结果截断和排序规则由 ToolCallPersistenceService 封装。</p>
 */
public interface ToolCallMapper extends BaseMapper<ToolCallEntity> {

    @Update("""
            UPDATE bq_tool_calls
            SET execution_id = #{executionId}
            WHERE turn_id = #{turnId}
              AND tool_call_id = #{toolCallId}
              AND execution_id IS NULL
              AND ((#{scoped} = 0 AND desktop_instance_id IS NULL)
                OR (#{scoped} = 1
                  AND desktop_instance_id = #{desktopInstanceId}
                  AND desktop_session_id = #{desktopSessionId}
                  AND auth_session_id = #{authSessionId}
                  AND identity_epoch = #{identityEpoch}
                  AND user_id = #{userId}
                  AND tenant_id = #{tenantId}
                  AND platform_id = #{platformId}))
            """)
    int bindExecutionIdIfUnbound(
            @Param("turnId") String turnId,
            @Param("toolCallId") String toolCallId,
            @Param("executionId") String executionId,
            @Param("scoped") int scoped,
            @Param("desktopInstanceId") String desktopInstanceId,
            @Param("desktopSessionId") String desktopSessionId,
            @Param("authSessionId") String authSessionId,
            @Param("identityEpoch") Long identityEpoch,
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("platformId") String platformId);

    @Select("""
            SELECT tc.*
            FROM bq_tool_calls tc
            JOIN bq_turns t ON t.turn_id = tc.turn_id AND t.thread_id = tc.thread_id
            WHERE tc.turn_id = #{turnId}
              AND ((#{scoped} = 0 AND t.desktop_instance_id IS NULL AND tc.desktop_instance_id IS NULL)
                OR (#{scoped} = 1
                  AND t.desktop_instance_id = #{desktopInstanceId}
                  AND t.desktop_session_id = #{desktopSessionId}
                  AND t.auth_session_id = #{authSessionId}
                  AND t.identity_epoch = #{identityEpoch}
                  AND t.user_id = #{userId}
                  AND t.tenant_id = #{tenantId}
                  AND t.platform_id = #{platformId}
                  AND tc.desktop_instance_id = #{desktopInstanceId}
                  AND tc.desktop_session_id = #{desktopSessionId}
                  AND tc.auth_session_id = #{authSessionId}
                  AND tc.identity_epoch = #{identityEpoch}
                  AND tc.user_id = #{userId}
                  AND tc.tenant_id = #{tenantId}
                  AND tc.platform_id = #{platformId}))
            ORDER BY tc.started_at ASC
            """)
    List<ToolCallEntity> selectAuthorizedByTurnId(
            @Param("turnId") String turnId,
            @Param("scoped") int scoped,
            @Param("desktopInstanceId") String desktopInstanceId,
            @Param("desktopSessionId") String desktopSessionId,
            @Param("authSessionId") String authSessionId,
            @Param("identityEpoch") Long identityEpoch,
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("platformId") String platformId);

    /**
     * 按工具名聚合调用次数、失败次数和平均耗时。
     *
     * <p>耗时使用 SQLite julianday 计算毫秒差；没有 completed_at 的运行中工具不参与平均耗时，
     * 但仍计入 calls，便于用户看到未收口工具数量。</p>
     *
     * @param cutoff ISO-8601 时间字符串；为空时不做时间过滤
     * @param cwd 可选工作目录；为空时统计所有工作目录
     * @return 工具维度聚合行
     */
    @Select("""
            SELECT
                tc.tool_name AS toolName,
                COUNT(*) AS calls,
                COALESCE(SUM(CASE WHEN LOWER(tc.status) IN ('failed', 'denied') THEN 1 ELSE 0 END), 0) AS failures,
                COALESCE(ROUND(AVG(CASE
                    WHEN tc.completed_at IS NOT NULL
                    THEN (julianday(tc.completed_at) - julianday(tc.started_at)) * 86400000
                    ELSE NULL
                END)), 0) AS avgDurationMs
            FROM bq_tool_calls tc
            JOIN bq_turns t ON t.turn_id = tc.turn_id
            WHERE (#{cutoff} IS NULL OR t.started_at >= #{cutoff})
              AND (#{cwd} IS NULL OR t.cwd = #{cwd})
            GROUP BY tc.tool_name
            ORDER BY calls DESC, failures DESC, tc.tool_name ASC
            """)
    List<Map<String, Object>> selectObservabilityTools(@Param("cutoff") String cutoff, @Param("cwd") String cwd);
}
