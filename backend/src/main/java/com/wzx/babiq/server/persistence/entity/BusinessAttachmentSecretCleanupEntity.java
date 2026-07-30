package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** Durable tombstone for deletion of an opaque attachment SecretStore reference. */
@Getter
@Setter
@ToString(exclude = "secretRef")
@NoArgsConstructor
@TableName("bq_business_attachment_secret_cleanup")
public class BusinessAttachmentSecretCleanupEntity {
    @TableId("secret_ref") private String secretRef;
    @TableField("secret_kind") private String secretKind;
    @TableField("reason_code") private String reasonCode;
    @TableField("attempt_count") private Integer attemptCount;
    @TableField("created_at") private String createdAt;
    @TableField("updated_at") private String updatedAt;
    @TableField("last_attempt_at") private String lastAttemptAt;
    @TableField("last_result_code") private String lastResultCode;
}
