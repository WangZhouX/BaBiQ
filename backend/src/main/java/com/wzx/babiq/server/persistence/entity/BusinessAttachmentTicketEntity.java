package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** bq_business_attachment_tickets 的摘要票据实体，不保存 bearer ticket 明文。 */
@Getter
@Setter
@ToString(exclude = "ticketId")
@NoArgsConstructor
@TableName("bq_business_attachment_tickets")
public class BusinessAttachmentTicketEntity {
    @TableId("ticket_id") private String ticketId;
    @TableField("batch_id") private String batchId;
    @TableField("desktop_instance_id") private String desktopInstanceId;
    @TableField("desktop_session_id") private String desktopSessionId;
    @TableField("auth_session_id") private String authSessionId;
    @TableField("tenant_id") private String tenantId;
    @TableField("identity_generation") private Long identityGeneration;
    private String state;
    @TableField("expires_at") private String expiresAt;
    @TableField(value = "claimed_at", updateStrategy = FieldStrategy.ALWAYS) private String claimedAt;
    @TableField(value = "completed_at", updateStrategy = FieldStrategy.ALWAYS) private String completedAt;
    @TableField("updated_at") private String updatedAt;
}
