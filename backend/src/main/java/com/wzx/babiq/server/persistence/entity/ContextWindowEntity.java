package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * `bq_context_windows` 的 MyBatis-Plus 实体。
 *
 * <p>它只反映 SQLite 表结构；业务层请通过 ContextWindowRepository 读写，避免把 mapper 细节泄漏到 Agent 运行期。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_context_windows")
public class ContextWindowEntity {

    /** 数据库内部自增主键，只给 MyBatis-Plus 使用。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 threadId，运行时按它 upsert 当前窗口状态。 */
    @TableField("thread_id")
    private String threadId;

    /** 当前窗口序号，P3-2 初始为 0。 */
    @TableField("window_ordinal")
    private Integer windowOrdinal;

    /** 当前窗口引用的短期摘要 id；P3-2 暂为空。 */
    @TableField("active_summary_id")
    private String activeSummaryId;

    /** 当前模型上下文窗口 token 数。 */
    @TableField("model_context_window")
    private Integer modelContextWindow;

    /** 自动压缩阈值 token 数。 */
    @TableField("auto_compact_threshold")
    private Integer autoCompactThreshold;

    /** 最近一次上下文快照 id，UI 状态查询读取它。 */
    @TableField("last_snapshot_id")
    private String lastSnapshotId;

    @TableField("desktop_instance_id") private String desktopInstanceId;
    @TableField("desktop_session_id") private String desktopSessionId;
    @TableField("auth_session_id") private String authSessionId;
    @TableField("identity_epoch") private Long identityEpoch;
    @TableField("user_id") private String userId;
    @TableField("tenant_id") private String tenantId;
    @TableField("platform_id") private String platformId;

    /** 创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;

    /** 最近更新时间，ISO-8601 文本。 */
    @TableField("updated_at")
    private String updatedAt;
}
