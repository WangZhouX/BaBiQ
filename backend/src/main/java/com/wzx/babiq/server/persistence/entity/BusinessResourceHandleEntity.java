package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** bq_business_resource_handles 的非敏感资源索引实体。 */
@Getter
@Setter
@ToString(exclude = {"handleId", "storageRef"})
@NoArgsConstructor
@TableName("bq_business_resource_handles")
public class BusinessResourceHandleEntity {
    @TableId("handle_id") private String handleId;
    @TableField("desktop_instance_id") private String desktopInstanceId;
    @TableField("desktop_session_id") private String desktopSessionId;
    @TableField("auth_session_id") private String authSessionId;
    @TableField("tenant_id") private String tenantId;
    @TableField("identity_generation") private Long identityGeneration;
    @TableField("media_type") private String mediaType;
    @TableField("content_length") private Long contentLength;
    @TableField("storage_ref") private String storageRef;
    private String policy;
    @TableField("created_at") private String createdAt;
    @TableField("expires_at") private String expiresAt;
    @TableField(value = "revoked_at", updateStrategy = FieldStrategy.ALWAYS) private String revokedAt;
}
