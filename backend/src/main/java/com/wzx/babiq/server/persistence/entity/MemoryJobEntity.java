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
 * `bq_memory_jobs` 表对应的 MyBatis-Plus 实体。
 *
 * <p>长期记忆流水线不是在每轮 turn 同步调用模型，而是由调度器领取 Phase1/Phase2 job 后异步推进。
 * 这张表保存任务、lease、重试和 generation 历史，repository adapter 负责把它转换成领域层 record。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_memory_jobs")
public class MemoryJobEntity {

    /** 数据库内部自增主键；仅给 MyBatis-Plus 使用，不暴露给 JSON-RPC。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层任务 id；由长期记忆服务生成，UI 和运行审计用它定位任务。 */
    @TableField("job_id")
    private String jobId;

    /** 任务类型；PHASE1_EXTRACT 表示候选抽取，PHASE2_CONSOLIDATE 表示全局归并。 */
    @TableField("job_type")
    private String jobType;

    /** 去重键；Phase1 防止同一 thread 水位重复抽取，Phase2 使用 phase2:{generation} 保留历史。 */
    @TableField("job_key")
    private String jobKey;

    /** Phase2 归并代次；Phase1 通常为 null 或 0，Phase2 必须递增。 */
    private Integer generation;

    /** 来源会话 id；Phase2 是全局归并任务，允许为空。 */
    @TableField("thread_id")
    private String threadId;

    /** 来源 turn id；Phase1 可记录最近完成 turn，Phase2 允许为空。 */
    @TableField("turn_id")
    private String turnId;

    /** 当前任务状态；调度器只领取 PENDING 或 lease 过期的 RUNNING。 */
    private String status;

    /** 当前持有 lease 的 worker id；用于多调度器场景下避免重复执行。 */
    @TableField("worker_id")
    private String workerId;

    /** lease 到期时间；超过该时间后其他 worker 可以重新领取。 */
    @TableField("lease_until")
    private String leaseUntil;

    /** 已重试次数；失败后由流水线递增。 */
    @TableField("retry_count")
    private Integer retryCount;

    /** 最大重试次数；来自长期记忆配置快照。 */
    @TableField("max_retries")
    private Integer maxRetries;

    /** 输入水位；Phase1 使用 thread 更新时间，Phase2 使用候选集合水位。 */
    @TableField("input_watermark")
    private String inputWatermark;

    /** 最近一次错误摘要；成功或无输出时为空。 */
    @TableField("error_message")
    private String errorMessage;

    /** 任务创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;

    /** 任务开始执行时间；尚未领取时为空。 */
    @TableField("started_at")
    private String startedAt;

    /** 任务完成时间；运行中或待运行时为空。 */
    @TableField("completed_at")
    private String completedAt;

    /** 任务最近更新时间；每次状态流转都会刷新。 */
    @TableField("updated_at")
    private String updatedAt;
}
