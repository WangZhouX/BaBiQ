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
 * `bq_work_unit_goals` 表对应的 MyBatis-Plus 实体。
 *
 * <p>目标是工作容器里的任务批次。运行中追加的新目标会作为 pending 目标保留，
 * 等用户显式启动后才关联真实 team/orchestration 运行记录。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_work_unit_goals")
public class WorkUnitGoalEntity {

    /** 数据库内部自增主键，不暴露给协议层。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层目标 id，以 goal_ 开头。 */
    @TableField("goal_id")
    private String goalId;

    /** 所属工作容器 id。 */
    @TableField("work_unit_id")
    private String workUnitId;

    /** 所属 thread id。 */
    @TableField("thread_id")
    private String threadId;

    /** 目标正文。 */
    @TableField("goal_text")
    private String goalText;

    /** 目标状态。 */
    private String status;

    /** 真实执行引用类型：team 或 orchestration。 */
    @TableField("run_ref_type")
    private String runRefType;

    /** 真实执行引用 id。 */
    @TableField("run_ref_id")
    private String runRefId;

    /** 完成摘要。 */
    private String summary;

    /** 失败原因。 */
    @TableField("error_message")
    private String errorMessage;

    /** 创建时间。 */
    @TableField("created_at")
    private String createdAt;

    /** 启动时间。 */
    @TableField("started_at")
    private String startedAt;

    /** 完成时间。 */
    @TableField("completed_at")
    private String completedAt;
}
