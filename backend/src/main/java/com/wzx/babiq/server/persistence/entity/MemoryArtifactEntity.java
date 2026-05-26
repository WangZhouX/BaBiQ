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
 * `bq_memory_artifacts` 表对应的 MyBatis-Plus 实体。
 *
 * <p>SQLite 是长期记忆事实源，Markdown 文件只是镜像。该表保存 artifact 元数据、正文副本和 token 估算，
 * 让 read path 不必读取文件系统也能注入最新 memory_summary。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_memory_artifacts")
public class MemoryArtifactEntity {

    /** 数据库内部自增主键；仅供持久化层使用。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层 artifact id；UI、引用记录和快照审计通过它串联。 */
    @TableField("artifact_id")
    private String artifactId;

    /** artifact 类型；例如 MEMORY_SUMMARY、MEMORY_HANDBOOK、RAW_MEMORIES。 */
    @TableField("artifact_type")
    private String artifactType;

    /** Markdown 镜像相对路径；相对于长期记忆 rootDir。 */
    @TableField("artifact_path")
    private String artifactPath;

    /** 文件内容 hash；用于后续判断镜像是否漂移。 */
    @TableField("content_hash")
    private String contentHash;

    /** artifact 版本；通常等于 Phase2 generation。 */
    private Integer version;

    /** 生成该 artifact 的 Phase2 job id。 */
    @TableField("source_job_id")
    private String sourceJobId;

    /** 本版本消费的候选 id JSON 数组；用于审计 artifact 来源。 */
    @TableField("candidate_ids_json")
    private String candidateIdsJson;

    /** 摘要或正文副本；MEMORY_SUMMARY 会被 read path 直接读取。 */
    @TableField("summary_text")
    private String summaryText;

    /** artifact 文本 token 估算；用于上下文预算控制。 */
    @TableField("token_estimate")
    private Integer tokenEstimate;

    /** artifact 创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;

    /** artifact 最近更新时间。 */
    @TableField("updated_at")
    private String updatedAt;
}
