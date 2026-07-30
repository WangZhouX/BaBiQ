package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** bq_business_oa_sessions 的 MyBatis-Plus 实体；字段只保存非敏感会话索引。 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_business_oa_sessions")
public class OaSessionEntity {
    /** OA 会话主键。 */
    @TableId("auth_session_id") private String authSessionId;
    /** 桌面应用实例标识。 */
    @TableField("desktop_instance_id") private String desktopInstanceId;
    /** 桌面会话标识。 */
    @TableField("desktop_session_id") private String desktopSessionId;
    /** OA 用户标识。 */
    @TableField(value = "user_id", updateStrategy = FieldStrategy.ALWAYS) private String userId;
    /** OA 租户标识。 */
    @TableField(value = "tenant_id", updateStrategy = FieldStrategy.ALWAYS) private String tenantId;
    /** OA 平台标识。 */
    @TableField(value = "platform_id", updateStrategy = FieldStrategy.ALWAYS) private String platformId;
    /** 服务端会话阶段。 */
    private String phase;
    /** 会话并发版本。 */
    private Long generation;
    /** 当前凭据引用。 */
    @TableField(value = "active_credential_ref", updateStrategy = FieldStrategy.ALWAYS) private String activeCredentialRef;
    /** 安装事务暂存凭据引用。 */
    @TableField(value = "staged_credential_ref", updateStrategy = FieldStrategy.ALWAYS) private String stagedCredentialRef;
    /** 凭据封装版本。 */
    @TableField("credential_version") private Integer credentialVersion;
    /** 安装开始时间。 */
    @TableField(value = "install_started_at", updateStrategy = FieldStrategy.ALWAYS) private String installStartedAt;
    /** 安装完成时间。 */
    @TableField(value = "installed_at", updateStrategy = FieldStrategy.ALWAYS) private String installedAt;
    /** 脱离时间。 */
    @TableField(value = "detached_at", updateStrategy = FieldStrategy.ALWAYS) private String detachedAt;
    /** 撤销时间。 */
    @TableField(value = "revoked_at", updateStrategy = FieldStrategy.ALWAYS) private String revokedAt;
    /** 更新时间。 */
    @TableField("updated_at") private String updatedAt;
    /** 服务端生成的一次性安装事务标识。 */
    @TableField(value = "installation_id", updateStrategy = FieldStrategy.ALWAYS) private String installationId;
    /** 安装事务所属桌面实例。 */
    @TableField(value = "installation_owner_desktop_instance_id", updateStrategy = FieldStrategy.ALWAYS)
    private String installationOwnerDesktopInstanceId;
    /** 安装事务所属桌面会话。 */
    @TableField(value = "installation_owner_desktop_session_id", updateStrategy = FieldStrategy.ALWAYS)
    private String installationOwnerDesktopSessionId;
    /** 安装事务开始前的目标会话代次。 */
    @TableField("installation_target_generation") private Long installationTargetGeneration;
    /** 安装事务过期时间，默认从开始时间起 90 秒。 */
    @TableField(value = "installation_expires_at", updateStrategy = FieldStrategy.ALWAYS)
    private String installationExpiresAt;
}
