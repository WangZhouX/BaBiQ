package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ItemEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * `bq_items` 的 MyBatis-Plus Mapper。
 *
 * <p>item 是聊天历史恢复的主要数据源；Mapper 保持轻量，排序和幂等写入由 repository 处理。</p>
 */
public interface ItemMapper extends BaseMapper<ItemEntity> {

    @Select("""
            SELECT i.*
            FROM bq_items i
            JOIN bq_turns t ON t.turn_id = i.turn_id AND t.thread_id = i.thread_id
            WHERE i.thread_id = #{threadId}
              AND i.item_id = #{itemId}
              AND i.status <> 'removed'
              AND ((#{scoped} = 0 AND t.desktop_instance_id IS NULL)
                OR (#{scoped} = 1
                  AND t.desktop_instance_id = #{desktopInstanceId}
                  AND t.desktop_session_id = #{desktopSessionId}
                  AND t.auth_session_id = #{authSessionId}
                  AND t.identity_epoch = #{identityEpoch}
                  AND t.user_id = #{userId}
                  AND t.tenant_id = #{tenantId}
                  AND t.platform_id = #{platformId}))
            LIMIT 1
            """)
    ItemEntity selectAuthorizedThreadItem(
            @Param("threadId") String threadId,
            @Param("itemId") String itemId,
            @Param("scoped") int scoped,
            @Param("desktopInstanceId") String desktopInstanceId,
            @Param("desktopSessionId") String desktopSessionId,
            @Param("authSessionId") String authSessionId,
            @Param("identityEpoch") Long identityEpoch,
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("platformId") String platformId);

    @Select("""
            SELECT i.*
            FROM bq_items i
            JOIN bq_turns t ON t.turn_id = i.turn_id AND t.thread_id = i.thread_id
            WHERE i.thread_id = #{threadId}
              AND i.status <> 'removed'
              AND ((#{scoped} = 0 AND t.desktop_instance_id IS NULL)
                OR (#{scoped} = 1
                  AND t.desktop_instance_id = #{desktopInstanceId}
                  AND t.desktop_session_id = #{desktopSessionId}
                  AND t.auth_session_id = #{authSessionId}
                  AND t.identity_epoch = #{identityEpoch}
                  AND t.user_id = #{userId}
                  AND t.tenant_id = #{tenantId}
                  AND t.platform_id = #{platformId}))
              AND (#{beforeSequence} IS NULL OR i.sequence_no < #{beforeSequence})
            ORDER BY i.sequence_no DESC
            LIMIT #{limit}
            """)
    List<ItemEntity> selectAuthorizedThreadItems(
            @Param("threadId") String threadId,
            @Param("limit") int limit,
            @Param("beforeSequence") Integer beforeSequence,
            @Param("scoped") int scoped,
            @Param("desktopInstanceId") String desktopInstanceId,
            @Param("desktopSessionId") String desktopSessionId,
            @Param("authSessionId") String authSessionId,
            @Param("identityEpoch") Long identityEpoch,
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("platformId") String platformId);

    @Select("""
            SELECT COUNT(*)
            FROM bq_items i
            JOIN bq_turns t ON t.turn_id = i.turn_id AND t.thread_id = i.thread_id
            WHERE i.thread_id = #{threadId}
              AND i.status <> 'removed'
              AND ((#{scoped} = 0 AND t.desktop_instance_id IS NULL)
                OR (#{scoped} = 1
                  AND t.desktop_instance_id = #{desktopInstanceId}
                  AND t.desktop_session_id = #{desktopSessionId}
                  AND t.auth_session_id = #{authSessionId}
                  AND t.identity_epoch = #{identityEpoch}
                  AND t.user_id = #{userId}
                  AND t.tenant_id = #{tenantId}
                  AND t.platform_id = #{platformId}))
            """)
    long countAuthorizedThreadItems(
            @Param("threadId") String threadId,
            @Param("scoped") int scoped,
            @Param("desktopInstanceId") String desktopInstanceId,
            @Param("desktopSessionId") String desktopSessionId,
            @Param("authSessionId") String authSessionId,
            @Param("identityEpoch") Long identityEpoch,
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("platformId") String platformId);

    @Select("""
            SELECT i.*
            FROM bq_items i
            JOIN bq_turns t ON t.turn_id = i.turn_id AND t.thread_id = i.thread_id
            WHERE i.turn_id = #{turnId}
              AND ((#{scoped} = 0 AND t.desktop_instance_id IS NULL)
                OR (#{scoped} = 1
                  AND t.desktop_instance_id = #{desktopInstanceId}
                  AND t.desktop_session_id = #{desktopSessionId}
                  AND t.auth_session_id = #{authSessionId}
                  AND t.identity_epoch = #{identityEpoch}
                  AND t.user_id = #{userId}
                  AND t.tenant_id = #{tenantId}
                  AND t.platform_id = #{platformId}))
            ORDER BY i.sequence_no ASC
            """)
    List<ItemEntity> selectAuthorizedTurnItems(
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
