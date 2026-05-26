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
 * `bq_memory_references` 表对应的 MyBatis-Plus 实体。
 *
 * <p>每次 ContextWindowRuntime 把长期记忆注入模型前，都会写引用记录。这样用户能追溯某个 turn
 * 实际看过哪个 memory_summary，也能统计 artifact 使用次数。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_memory_references")
public class MemoryReferenceEntity {

    /** 数据库内部自增主键；不暴露给 UI。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层引用 id；由 read path 写入引用记录时生成。 */
    @TableField("reference_id")
    private String referenceId;

    /** 当前 thread id；表示哪段会话读取了长期记忆。 */
    @TableField("thread_id")
    private String threadId;

    /** 当前 turn id；表示哪轮模型调用读取了长期记忆。 */
    @TableField("turn_id")
    private String turnId;

    /** 当前上下文快照 id；用于从快照反查注入内容。 */
    @TableField("snapshot_id")
    private String snapshotId;

    /** 被引用的 artifact id；summary 注入通常引用 MEMORY_SUMMARY artifact。 */
    @TableField("artifact_id")
    private String artifactId;

    /** 被引用的候选 id；summary 级引用通常为空，候选级审计时才写入。 */
    @TableField("candidate_id")
    private String candidateId;

    /** 引用类型；例如 SUMMARY，后续可扩展 USER_VIEWED 或 PHASE2_SELECTED。 */
    @TableField("reference_type")
    private String referenceType;

    /** 本次注入消耗的 token 估算。 */
    @TableField("token_estimate")
    private Integer tokenEstimate;

    /** 引用创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;
}
