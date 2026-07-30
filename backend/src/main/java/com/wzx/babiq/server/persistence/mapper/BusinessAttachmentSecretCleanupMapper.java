package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.BusinessAttachmentSecretCleanupEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BusinessAttachmentSecretCleanupMapper
        extends BaseMapper<BusinessAttachmentSecretCleanupEntity> {
    @Insert("""
            INSERT INTO bq_business_attachment_secret_cleanup(
                secret_ref, secret_kind, reason_code, attempt_count,
                created_at, updated_at, last_attempt_at, last_result_code)
            VALUES (#{secretRef}, #{secretKind}, #{reasonCode}, 0, #{now}, #{now}, NULL, NULL)
            ON CONFLICT(secret_ref) DO UPDATE SET
                secret_kind = excluded.secret_kind,
                reason_code = excluded.reason_code,
                updated_at = excluded.updated_at
            """)
    int upsertPending(@Param("secretRef") String secretRef,
                      @Param("secretKind") String secretKind,
                      @Param("reasonCode") String reasonCode,
                      @Param("now") String now);

    @Update("""
            UPDATE bq_business_attachment_secret_cleanup
            SET attempt_count = attempt_count + 1,
                last_attempt_at = #{now},
                last_result_code = 'SECRET_STORE_DELETE_FAILED',
                updated_at = #{now}
            WHERE secret_ref = #{secretRef}
            """)
    int recordFailure(@Param("secretRef") String secretRef, @Param("now") String now);

    @Select("""
            SELECT secret_ref, secret_kind, reason_code, attempt_count,
                   created_at, updated_at, last_attempt_at, last_result_code
            FROM bq_business_attachment_secret_cleanup
            ORDER BY updated_at ASC, secret_ref ASC
            LIMIT #{limit}
            """)
    List<BusinessAttachmentSecretCleanupEntity> selectPending(@Param("limit") int limit);

    @Delete("DELETE FROM bq_business_attachment_secret_cleanup WHERE secret_ref = #{secretRef}")
    int deleteTombstone(@Param("secretRef") String secretRef);
}
