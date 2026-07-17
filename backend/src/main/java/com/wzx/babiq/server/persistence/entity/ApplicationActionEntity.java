package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("bq_application_actions")
public class ApplicationActionEntity {
    @TableId(value = "execution_id", type = IdType.INPUT)
    private String executionId;
    @TableField("action_id") private String actionId;
    @TableField("action_version") private Integer actionVersion;
    @TableField("request_fingerprint") private String requestFingerprint;
    @TableField("thread_id") private String threadId;
    @TableField("turn_id") private String turnId;
    @TableField("tool_call_id") private String toolCallId;
    @TableField("desktop_instance_id") private String desktopInstanceId;
    @TableField("desktop_session_id") private String desktopSessionId;
    @TableField("auth_session_id") private String authSessionId;
    @TableField("identity_epoch") private Long identityEpoch;
    @TableField("user_id") private String userId;
    @TableField("tenant_id") private String tenantId;
    @TableField("platform_id") private String platformId;
    private String status;
    @TableField("result_summary_redacted") private String resultSummaryRedacted;
    @TableField("error_code") private String errorCode;
    @TableField("error_message_redacted") private String errorMessageRedacted;
    @TableField("created_at") private String createdAt;
    @TableField("updated_at") private String updatedAt;
    @TableField("terminal_at") private String terminalAt;
}
