package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(exclude = "requestFingerprint")
@NoArgsConstructor
@TableName("bq_business_schedule_operations")
public class BusinessScheduleOperationEntity {
    @TableId("operation_id") private String operationId;
    @TableField("desktop_instance_id") private String desktopInstanceId;
    @TableField("desktop_session_id") private String desktopSessionId;
    @TableField("auth_session_id") private String authSessionId;
    @TableField("tenant_id") private String tenantId;
    @TableField("identity_generation") private Long identityGeneration;
    @TableField("client_operation_id") private String clientOperationId;
    @TableField("actor_user_id") private String actorUserId;
    @TableField("form_revision") private Long formRevision;
    @TableField(value = "attachment_batch_id", updateStrategy = FieldStrategy.ALWAYS)
    private String attachmentBatchId;
    @TableField("request_fingerprint") private String requestFingerprint;
    private String state;
    @TableField(value = "result_revision", updateStrategy = FieldStrategy.ALWAYS)
    private Long resultRevision;
    @TableField("created_at") private String createdAt;
    @TableField("updated_at") private String updatedAt;
}
