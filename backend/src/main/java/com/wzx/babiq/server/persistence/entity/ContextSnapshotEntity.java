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
 * `bq_context_snapshots` 的 MyBatis-Plus 实体。
 *
 * <p>该表保存模型调用前的临时上下文视图，便于 UI 审计和后续压缩决策，不改变原始聊天历史。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_context_snapshots")
public class ContextSnapshotEntity {

    /** 数据库内部自增主键，只给 MyBatis-Plus 使用。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层快照 id。 */
    @TableField("snapshot_id")
    private String snapshotId;

    /** 所属会话 threadId。 */
    @TableField("thread_id")
    private String threadId;

    /** 所属 turnId。 */
    @TableField("turn_id")
    private String turnId;

    /** 快照阶段，P3-2 固定为 pre_model_call。 */
    private String phase;

    /** 本轮 Provider id。 */
    @TableField("provider_id")
    private String providerId;

    /** 本轮模型名。 */
    private String model;

    /** 本轮工作目录。 */
    private String cwd;

    /** 所属窗口序号。 */
    @TableField("window_ordinal")
    private Integer windowOrdinal;

    /** 模型上下文窗口 token 数。 */
    @TableField("model_context_window")
    private Integer modelContextWindow;

    /** 自动压缩阈值 token 数。 */
    @TableField("auto_compact_threshold")
    private Integer autoCompactThreshold;

    /** 预估上下文 token 数。 */
    @TableField("estimated_tokens")
    private Integer estimatedTokens;

    /** 模型返回的真实 prompt token；供应商未返回时为空。 */
    @TableField("actual_prompt_tokens")
    private Long actualPromptTokens;

    /** 纳入模型输入的片段数量。 */
    @TableField("included_item_count")
    private Integer includedItemCount;

    /** 排除但记录审计的片段数量。 */
    @TableField("excluded_item_count")
    private Integer excludedItemCount;

    /** 分层上下文 envelope JSON。 */
    @TableField("envelope_json")
    private String envelopeJson;

    /** 快照条目 JSON。 */
    @TableField("items_json")
    private String itemsJson;

    /** 能力目录摘要 JSON。 */
    @TableField("capability_catalog_json")
    private String capabilityCatalogJson;

    /** 本轮注入的长期记忆引用 JSON；为空表示未引用长期记忆。 */
    @TableField("long_term_memory_refs_json")
    private String longTermMemoryRefsJson;

    /** 长期记忆引用 token 估算；未引用时为 0。 */
    @TableField("long_term_memory_token_estimate")
    private Integer longTermMemoryTokenEstimate;

    /** 本轮用户输入预览。 */
    @TableField("input_preview")
    private String inputPreview;

    /** 快照创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;
}
