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
 * `bq_context_compactions` 的 MyBatis-Plus 实体。
 *
 * <p>它记录每一次自动压缩尝试，成功、跳过和失败都保留，便于复盘为什么某一轮上下文被改写。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_context_compactions")
public class ContextCompactionEntity {

    /** 数据库内部自增主键，只给 MyBatis-Plus 使用。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层压缩尝试 id，以 ctxcmp_ 开头。 */
    @TableField("compaction_id")
    private String compactionId;

    /** 所属会话 threadId。 */
    @TableField("thread_id")
    private String threadId;

    /** 触发压缩的 turnId。 */
    @TableField("turn_id")
    private String turnId;

    /** 压缩状态：SUCCESS、SKIPPED 或 FAILED。 */
    @TableField("status")
    private String status;

    /** 成功时生成的摘要 id，失败或跳过时为空。 */
    @TableField("summary_id")
    private String summaryId;

    /** 本次压缩覆盖的 item 范围。 */
    @TableField("source_item_range")
    private String sourceItemRange;

    /** 本次压缩起点 item id。 */
    @TableField("source_start_item_id")
    private String sourceStartItemId;

    /** 本次压缩终点 item id。 */
    @TableField("source_end_item_id")
    private String sourceEndItemId;

    /** 压缩前本轮上下文预估 token。 */
    @TableField("estimated_tokens_before")
    private Integer estimatedTokensBefore;

    /** 摘要正文预估 token，失败或跳过时为 0。 */
    @TableField("estimated_tokens_after")
    private Integer estimatedTokensAfter;

    /** 失败或跳过原因，成功时为空。 */
    @TableField("error_message")
    private String errorMessage;

    /** 创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;

    /** 压缩触发类型：AUTO_PRE_TURN、MANUAL 或 FORCE_GUARD。 */
    @TableField("trigger_type")
    private String triggerType;

    /** 压缩前窗口序号，用于乐观锁和审计回放。 */
    @TableField("previous_window_ordinal")
    private Integer previousWindowOrdinal;

    /** 压缩成功安装后的窗口序号，失败或跳过时可为空。 */
    @TableField("next_window_ordinal")
    private Integer nextWindowOrdinal;

    /** 触发压缩时用于估算的输入快照 id。 */
    @TableField("input_snapshot_id")
    private String inputSnapshotId;

    /** 压缩成功后替换窗口指向的快照 id。 */
    @TableField("replacement_snapshot_id")
    private String replacementSnapshotId;

    /** 本次压缩判定时采用的模型上下文窗口 token 数。 */
    @TableField("model_context_window")
    private Integer modelContextWindow;

    /** 本次压缩判定时的有效输入预算 token 数。 */
    @TableField("effective_input_budget")
    private Integer effectiveInputBudget;

    /** 本次压缩判定时的自动压缩阈值 token 数。 */
    @TableField("auto_compact_threshold")
    private Integer autoCompactThreshold;

    /** 压缩尝试开始时间，ISO-8601 文本。 */
    @TableField("started_at")
    private String startedAt;

    /** 压缩尝试结束时间，ISO-8601 文本。 */
    @TableField("completed_at")
    private String completedAt;
}
