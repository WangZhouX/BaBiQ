package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** bq_business_attachment_batches 的非敏感持久化实体。 */
@Getter
@Setter
@ToString(exclude = {"fileIdSecretRef", "declarationSecretRef"})
@NoArgsConstructor
@TableName("bq_business_attachment_batches")
public class BusinessAttachmentBatchEntity {
    @TableId("batch_id") private String batchId;
    @TableField("desktop_instance_id") private String desktopInstanceId;
    @TableField("desktop_session_id") private String desktopSessionId;
    @TableField("auth_session_id") private String authSessionId;
    @TableField("tenant_id") private String tenantId;
    @TableField("identity_generation") private Long identityGeneration;
    private String operation;
    @TableField("client_operation_id") private String clientOperationId;
    @TableField("actor_user_id") private String actorUserId;
    private String scope;
    @TableField(value = "team_id", updateStrategy = FieldStrategy.ALWAYS) private String teamId;
    @TableField("schedule_type_id") private String scheduleTypeId;
    @TableField("parent_relation_type") private String parentRelationType;
    @TableField("parent_resource_id") private String parentResourceId;
    @TableField(value = "parent_record_id", updateStrategy = FieldStrategy.ALWAYS) private String parentRecordId;
    @TableField(value = "form_revision", updateStrategy = FieldStrategy.ALWAYS) private String formRevision;
    @TableField("declaration_secret_ref") private String declarationSecretRef;
    private String state;
    @TableField(value = "file_id_secret_ref", updateStrategy = FieldStrategy.ALWAYS) private String fileIdSecretRef;
    @TableField("expires_at") private String expiresAt;
    @TableField("created_at") private String createdAt;
    @TableField("updated_at") private String updatedAt;
}
