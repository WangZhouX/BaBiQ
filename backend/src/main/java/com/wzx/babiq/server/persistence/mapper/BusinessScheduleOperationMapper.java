package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.BusinessScheduleOperationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BusinessScheduleOperationMapper extends BaseMapper<BusinessScheduleOperationEntity> {
    @Insert("""
            INSERT OR IGNORE INTO bq_business_schedule_operations(
                operation_id, desktop_instance_id, desktop_session_id, auth_session_id, tenant_id,
                identity_generation, client_operation_id, actor_user_id, form_revision,
                attachment_batch_id, request_fingerprint, state, result_revision, created_at, updated_at)
            VALUES(
                #{operationId}, #{desktopInstanceId}, #{desktopSessionId}, #{authSessionId}, #{tenantId},
                #{identityGeneration}, #{clientOperationId}, #{actorUserId}, #{formRevision},
                #{attachmentBatchId}, #{requestFingerprint}, #{state}, #{resultRevision}, #{createdAt}, #{updatedAt})
            """)
    int insertIgnore(BusinessScheduleOperationEntity entity);
}
