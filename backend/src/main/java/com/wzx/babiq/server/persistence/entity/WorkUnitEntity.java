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
 * `bq_work_units` 表对应的 MyBatis-Plus 实体。
 *
 * <p>该表保存 P6-4 slash 创建的命名工作容器。容器本身只表示“待配置/待启动/运行中”等状态，
 * 不替代 P6-2/P6-3 的真实编排和团队运行事实。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_work_units")
public class WorkUnitEntity {

    /** 数据库内部自增主键，不暴露给协议层。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层工作容器 id，以 wu_ 开头。 */
    @TableField("work_unit_id")
    private String workUnitId;

    /** 所属 thread id。 */
    @TableField("thread_id")
    private String threadId;

    /** 容器类型：orchestration 或 team。 */
    private String kind;

    /** 用户可读名称。 */
    private String name;

    /** 归一化名称，用于同名复用。 */
    @TableField("normalized_name")
    private String normalizedName;

    /** 容器状态。 */
    private String status;

    /** 当前正在运行或最近激活的目标 id。 */
    @TableField("current_goal_id")
    private String currentGoalId;

    /** 创建时工作目录快照。 */
    private String cwd;

    /** 创建时沙箱模式快照。 */
    @TableField("sandbox_mode")
    private String sandboxMode;

    /** 是否已从 UI 移除。 */
    private Integer removed;

    /** 从 UI 移除的时间。 */
    @TableField("removed_at")
    private String removedAt;

    /** 创建时间。 */
    @TableField("created_at")
    private String createdAt;

    /** 更新时间。 */
    @TableField("updated_at")
    private String updatedAt;
}
