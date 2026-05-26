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
 * `bq_memory_candidates` 表对应的 MyBatis-Plus 实体。
 *
 * <p>候选记忆是 Phase1 的产物，只有通过脱敏并保持 CLEAN 的候选才允许进入 Phase2。
 * 这层实体只描述 SQLite 字段，污染判定、脱敏和候选选择规则都留在 memory application service 中。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_memory_candidates")
public class MemoryCandidateEntity {

    /** 数据库内部自增主键；不暴露给上层协议。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层候选 id；由 Phase1 保存候选时生成。 */
    @TableField("candidate_id")
    private String candidateId;

    /** 来源 thread id；用于回溯候选来自哪段会话。 */
    @TableField("thread_id")
    private String threadId;

    /** 来源 turn id；如果 Phase1 只按 thread 水位抽取，可为空。 */
    @TableField("turn_id")
    private String turnId;

    /** 生成该候选的 Phase1 job id；用于审计任务和候选的关系。 */
    @TableField("job_id")
    private String jobId;

    /** 候选来源工作目录；后续可按项目隔离或筛选长期记忆。 */
    private String cwd;

    /** 抽取时使用的 provider id；用于排查模型差异导致的抽取质量问题。 */
    @TableField("provider_id")
    private String providerId;

    /** 抽取时使用的模型名；和 providerId 一起构成可审计的生成来源。 */
    private String model;

    /** 脱敏后的原始长期记忆正文；Phase2 的核心输入之一。 */
    @TableField("raw_memory")
    private String rawMemory;

    /** 当前会话片段摘要；Java 镜像器会写入 rollout_summaries。 */
    @TableField("rollout_summary")
    private String rolloutSummary;

    /** rollout summary 文件 slug；为空时由镜像器生成安全文件名。 */
    @TableField("rollout_slug")
    private String rolloutSlug;

    /** 来源 item id JSON 数组；用于解释候选依据来自哪些原始消息或工具结果。 */
    @TableField("source_item_ids_json")
    private String sourceItemIdsJson;

    /** 来源上下文快照 id；为空表示本次抽取没有绑定具体快照。 */
    @TableField("source_snapshot_id")
    private String sourceSnapshotId;

    /** 污染状态；只有 CLEAN 会进入自动 Phase2。 */
    @TableField("pollution_status")
    private String pollutionStatus;

    /** 脱敏命中次数；过高时会被标记为 SECRET_RISK。 */
    @TableField("redaction_count")
    private Integer redactionCount;

    /** 是否已被 Phase2 选中；SQLite 使用 0/1 保存。 */
    @TableField("selected_for_phase2")
    private Integer selectedForPhase2;

    /** 被 Phase2 选中的时间；未归并时为空。 */
    @TableField("selected_at")
    private String selectedAt;

    /** read path 或归并选择的引用次数；用于后续排序策略。 */
    @TableField("usage_count")
    private Integer usageCount;

    /** 最近一次被 read path 引用的时间；从未引用时为空。 */
    @TableField("last_used_at")
    private String lastUsedAt;

    /** 候选创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;

    /** 候选最近更新时间；归并选择或引用计数变化时刷新。 */
    @TableField("updated_at")
    private String updatedAt;
}
