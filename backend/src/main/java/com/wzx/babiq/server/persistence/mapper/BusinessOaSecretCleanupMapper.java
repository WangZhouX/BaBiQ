package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.BusinessOaSecretCleanupEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** OA SecretStore 引用耐久清理 mapper。 */
@Mapper
public interface BusinessOaSecretCleanupMapper extends BaseMapper<BusinessOaSecretCleanupEntity> {

    @Insert("""
            INSERT INTO bq_business_oa_secret_cleanup(
                secret_ref, auth_session_id, state, reason_code, operation_id, attempt_count,
                created_at, updated_at, last_attempt_at, last_result_code)
            VALUES (#{secretRef}, #{authSessionId}, 'RESERVED', #{reasonCode}, #{operationId}, 0,
                    #{now}, #{now}, NULL, NULL)
            ON CONFLICT(secret_ref) DO UPDATE SET
                auth_session_id = excluded.auth_session_id,
                state = 'RESERVED',
                reason_code = excluded.reason_code,
                operation_id = excluded.operation_id,
                attempt_count = 0,
                updated_at = excluded.updated_at,
                last_attempt_at = NULL,
                last_result_code = NULL
            WHERE bq_business_oa_secret_cleanup.state = 'RESERVED'
              AND bq_business_oa_secret_cleanup.auth_session_id = excluded.auth_session_id
            """)
    int upsertReserved(
            @Param("secretRef") String secretRef,
            @Param("authSessionId") String authSessionId,
            @Param("reasonCode") String reasonCode,
            @Param("operationId") String operationId,
            @Param("now") String now);

    @Delete("""
            DELETE FROM bq_business_oa_secret_cleanup
            WHERE secret_ref = #{secretRef}
              AND auth_session_id = #{authSessionId}
              AND state = 'RESERVED'
            """)
    int consumeReserved(
            @Param("secretRef") String secretRef,
            @Param("authSessionId") String authSessionId);

    @Insert("""
            INSERT INTO bq_business_oa_secret_cleanup(
                secret_ref, auth_session_id, state, reason_code, operation_id, attempt_count,
                created_at, updated_at, last_attempt_at, last_result_code)
            VALUES (#{secretRef}, #{authSessionId}, 'DELETE_PENDING', #{reasonCode}, #{operationId}, 0,
                    #{now}, #{now}, NULL, NULL)
            ON CONFLICT(secret_ref) DO UPDATE SET
                state = 'DELETE_PENDING',
                reason_code = excluded.reason_code,
                operation_id = excluded.operation_id,
                updated_at = excluded.updated_at
            WHERE bq_business_oa_secret_cleanup.auth_session_id = excluded.auth_session_id
            """)
    int upsertDeletePending(
            @Param("secretRef") String secretRef,
            @Param("authSessionId") String authSessionId,
            @Param("reasonCode") String reasonCode,
            @Param("operationId") String operationId,
            @Param("now") String now);

    @Update("""
            UPDATE bq_business_oa_secret_cleanup
            SET state = 'DELETE_PENDING',
                reason_code = #{reasonCode},
                operation_id = #{operationId},
                updated_at = #{now}
            WHERE secret_ref = #{secretRef} AND state = 'RESERVED'
            """)
    int markDeletePending(
            @Param("secretRef") String secretRef,
            @Param("reasonCode") String reasonCode,
            @Param("operationId") String operationId,
            @Param("now") String now);

    @Update("""
            UPDATE bq_business_oa_secret_cleanup
            SET state = 'DELETE_PENDING',
                reason_code = #{reasonCode},
                operation_id = #{operationId},
                updated_at = #{now}
            WHERE secret_ref = #{secretRef}
              AND auth_session_id = #{authSessionId}
              AND state = 'RESERVED'
            """)
    int markReservedDeletePending(
            @Param("secretRef") String secretRef,
            @Param("authSessionId") String authSessionId,
            @Param("reasonCode") String reasonCode,
            @Param("operationId") String operationId,
            @Param("now") String now);

    @Update("""
            UPDATE bq_business_oa_secret_cleanup
            SET attempt_count = attempt_count + 1,
                last_attempt_at = #{attemptedAt},
                last_result_code = #{resultCode},
                updated_at = #{attemptedAt}
            WHERE secret_ref = #{secretRef} AND state = 'DELETE_PENDING'
            """)
    int recordDeleteFailure(
            @Param("secretRef") String secretRef,
            @Param("resultCode") String resultCode,
            @Param("attemptedAt") String attemptedAt);

    @Select("""
            SELECT secret_ref, auth_session_id, state, reason_code, operation_id,
                   attempt_count, created_at, updated_at, last_attempt_at, last_result_code
            FROM bq_business_oa_secret_cleanup
            WHERE state = 'DELETE_PENDING'
            ORDER BY updated_at ASC, secret_ref ASC
            LIMIT #{limit}
            """)
    List<BusinessOaSecretCleanupEntity> selectDeletePendingBatch(@Param("limit") int limit);

    @Delete("""
            DELETE FROM bq_business_oa_secret_cleanup
            WHERE secret_ref = #{secretRef} AND state = 'DELETE_PENDING'
            """)
    int deleteTombstone(@Param("secretRef") String secretRef);
}
