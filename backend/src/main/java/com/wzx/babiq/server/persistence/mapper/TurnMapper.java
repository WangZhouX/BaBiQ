package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * `bq_turns` 的 MyBatis-Plus Mapper。
 *
 * <p>后续恢复未完成 turn、查询运行记录时都会从这里出发，但具体查询组合由 service 封装。</p>
 */
public interface TurnMapper extends BaseMapper<TurnEntity> {

    /**
     * 聚合统计窗口内的 turn 总量、失败数和 token。
     *
     * @param cutoff ISO-8601 时间字符串；为空时不做时间过滤
     * @param cwd 可选工作目录；为空时统计所有工作目录
     * @return 单行聚合结果
     */
    @Select("""
            SELECT
                COUNT(*) AS turns,
                COALESCE(SUM(CASE WHEN UPPER(t.status) = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedTurns,
                COALESCE(SUM(COALESCE(s.prompt_tokens, 0)), 0) AS promptTokens,
                COALESCE(SUM(COALESCE(s.completion_tokens, 0)), 0) AS completionTokens,
                COALESCE(SUM(COALESCE(s.total_tokens, 0)), 0) AS totalTokens
            FROM bq_turns t
            LEFT JOIN bq_turn_summaries s ON s.turn_id = t.turn_id
            WHERE (#{cutoff} IS NULL OR t.started_at >= #{cutoff})
              AND (#{cwd} IS NULL OR t.cwd = #{cwd})
            """)
    Map<String, Object> selectObservabilityTotals(@Param("cutoff") String cutoff, @Param("cwd") String cwd);

    /**
     * 按 Provider 聚合 token 用量。
     *
     * @param cutoff ISO-8601 时间字符串；为空时不做时间过滤
     * @param cwd 可选工作目录；为空时统计所有工作目录
     * @return Provider 维度聚合行
     */
    @Select("""
            SELECT
                t.provider_id AS providerId,
                NULL AS model,
                COUNT(*) AS turns,
                COALESCE(SUM(CASE WHEN UPPER(t.status) = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedTurns,
                COALESCE(SUM(COALESCE(s.prompt_tokens, 0)), 0) AS promptTokens,
                COALESCE(SUM(COALESCE(s.completion_tokens, 0)), 0) AS completionTokens,
                COALESCE(SUM(COALESCE(s.total_tokens, 0)), 0) AS totalTokens
            FROM bq_turns t
            LEFT JOIN bq_turn_summaries s ON s.turn_id = t.turn_id
            WHERE (#{cutoff} IS NULL OR t.started_at >= #{cutoff})
              AND (#{cwd} IS NULL OR t.cwd = #{cwd})
            GROUP BY t.provider_id
            ORDER BY totalTokens DESC, turns DESC, t.provider_id ASC
            """)
    List<Map<String, Object>> selectObservabilityByProvider(@Param("cutoff") String cutoff, @Param("cwd") String cwd);

    /**
     * 按 Provider/Model 聚合 token 用量。
     *
     * @param cutoff ISO-8601 时间字符串；为空时不做时间过滤
     * @param cwd 可选工作目录；为空时统计所有工作目录
     * @return Provider/Model 维度聚合行
     */
    @Select("""
            SELECT
                t.provider_id AS providerId,
                t.model AS model,
                COUNT(*) AS turns,
                COALESCE(SUM(CASE WHEN UPPER(t.status) = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedTurns,
                COALESCE(SUM(COALESCE(s.prompt_tokens, 0)), 0) AS promptTokens,
                COALESCE(SUM(COALESCE(s.completion_tokens, 0)), 0) AS completionTokens,
                COALESCE(SUM(COALESCE(s.total_tokens, 0)), 0) AS totalTokens
            FROM bq_turns t
            LEFT JOIN bq_turn_summaries s ON s.turn_id = t.turn_id
            WHERE (#{cutoff} IS NULL OR t.started_at >= #{cutoff})
              AND (#{cwd} IS NULL OR t.cwd = #{cwd})
            GROUP BY t.provider_id, t.model
            ORDER BY totalTokens DESC, turns DESC, t.provider_id ASC, t.model ASC
            """)
    List<Map<String, Object>> selectObservabilityByModel(@Param("cutoff") String cutoff, @Param("cwd") String cwd);

    /**
     * 按 turn 状态聚合数量。
     *
     * @param cutoff ISO-8601 时间字符串；为空时不做时间过滤
     * @param cwd 可选工作目录；为空时统计所有工作目录
     * @return 状态维度聚合行
     */
    @Select("""
            SELECT
                t.status AS status,
                COUNT(*) AS turns
            FROM bq_turns t
            WHERE (#{cutoff} IS NULL OR t.started_at >= #{cutoff})
              AND (#{cwd} IS NULL OR t.cwd = #{cwd})
            GROUP BY t.status
            ORDER BY turns DESC, t.status ASC
            """)
    List<Map<String, Object>> selectObservabilityByStatus(@Param("cutoff") String cutoff, @Param("cwd") String cwd);
}
