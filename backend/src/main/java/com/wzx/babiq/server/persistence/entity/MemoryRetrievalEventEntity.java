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
 * `bq_memory_retrieval_events` 表对应的 MyBatis-Plus 实体。
 *
 * <p>长期记忆检索片段是 reference 层输入，必须可追溯来源、策略和 token 预算，不能只存在 prompt 文本里。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_memory_retrieval_events")
public class MemoryRetrievalEventEntity {

    /** 数据库内部自增主键；仅供持久化层使用。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层检索事件 id。 */
    @TableField("retrieval_id")
    private String retrievalId;

    /** 来源 thread id。 */
    @TableField("thread_id")
    private String threadId;

    /** 来源 turn id。 */
    @TableField("turn_id")
    private String turnId;

    /** 当前上下文快照 id；快照落库失败时可为空。 */
    @TableField("snapshot_id")
    private String snapshotId;

    /** 从本轮用户输入派生的检索查询。 */
    @TableField("query_text")
    private String queryText;

    /** 检索策略，LEXICAL、VECTOR_STORE 或 HYBRID。 */
    private String strategy;

    /** 初筛候选数量。 */
    @TableField("candidate_count")
    private Integer candidateCount;

    /** 被注入的 artifact/candidate/reference id JSON 数组。 */
    @TableField("selected_references_json")
    private String selectedReferencesJson;

    /** 本次注入片段 token 估算。 */
    @TableField("token_estimate")
    private Integer tokenEstimate;

    /** 污染或低可信标记 JSON 数组。 */
    @TableField("pollution_flags_json")
    private String pollutionFlagsJson;

    /** 事件创建时间。 */
    @TableField("created_at")
    private String createdAt;
}
