package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** bq_business_oa_secret_cleanup 的 MyBatis-Plus 实体；不得生成包含 secret_ref 的 toString。 */
@Getter
@Setter
@NoArgsConstructor
@TableName("bq_business_oa_secret_cleanup")
public class BusinessOaSecretCleanupEntity {
    /** SecretStore 不透明引用主键；不含任何凭据内容。 */
    @JsonIgnore
    @TableId("secret_ref") private String secretRef;
    /** 引用所属 OA 认证会话。 */
    @TableField("auth_session_id") private String authSessionId;
    /** RESERVED 或 DELETE_PENDING。 */
    private String state;
    /** 固定内部原因码。 */
    @TableField("reason_code") private String reasonCode;
    /** 可选业务操作标识。 */
    @TableField(value = "operation_id", updateStrategy = FieldStrategy.ALWAYS) private String operationId;
    /** 已失败删除次数。 */
    @TableField("attempt_count") private Integer attemptCount;
    /** tombstone 首次创建时间。 */
    @TableField("created_at") private String createdAt;
    /** 最近状态更新时间。 */
    @TableField("updated_at") private String updatedAt;
    /** 最近一次失败删除时间。 */
    @TableField(value = "last_attempt_at", updateStrategy = FieldStrategy.ALWAYS) private String lastAttemptAt;
    /** 最近一次固定内部结果码。 */
    @TableField(value = "last_result_code", updateStrategy = FieldStrategy.ALWAYS) private String lastResultCode;
}
