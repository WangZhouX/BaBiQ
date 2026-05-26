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
 * `bq_context_summaries` 的 MyBatis-Plus 实体。
 *
 * <p>该表保存短期压缩摘要正文；Agent 运行时通过 ContextSummaryRepository 读取，
 * 不直接依赖 mapper 或数据库字段名。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_context_summaries")
public class ContextSummaryEntity {

    /** 数据库内部自增主键，只给 MyBatis-Plus 使用。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层摘要 id，以 ctxsum_ 开头，由 ContextCompactionService 生成。 */
    @TableField("summary_id")
    private String summaryId;

    /** 所属会话 threadId，用于按会话清理和审计。 */
    @TableField("thread_id")
    private String threadId;

    /** 摘要覆盖的 item 范围，给 UI 和日志快速展示。 */
    @TableField("source_item_range")
    private String sourceItemRange;

    /** 摘要覆盖的起点 item id，ContextAssembler 用于判断旧历史是否已被替换。 */
    @TableField("source_start_item_id")
    private String sourceStartItemId;

    /** 摘要覆盖的终点 item id，后续新历史从它之后继续注入模型。 */
    @TableField("source_end_item_id")
    private String sourceEndItemId;

    /** 摘要正文，由压缩策略生成，作为 short_term_summary 层注入模型。 */
    @TableField("summary")
    private String summary;

    /** 生成摘要时使用的 Provider id，便于排查模型差异。 */
    @TableField("provider_id")
    private String providerId;

    /** 生成摘要时使用的模型名，便于审计和复现。 */
    @TableField("model")
    private String model;

    /** 摘要正文预估 token 数，用于压缩效果观测。 */
    @TableField("estimated_tokens")
    private Integer estimatedTokens;

    /** 创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;
}
