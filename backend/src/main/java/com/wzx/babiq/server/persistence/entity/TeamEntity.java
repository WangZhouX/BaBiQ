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
 * `bq_teams` 表对应的 MyBatis-Plus 实体。
 *
 * <p>该表保存 P6-3 一次团队协作的整体运行状态，成员明细放在
 * `bq_team_members`，团队消息时间线放在 `bq_team_messages`。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_teams")
public class TeamEntity {

    /** 数据库内部自增主键，不暴露给协议层。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层团队 id，以 team_ 开头，由工具层生成并贯穿 UI 与运行记录。 */
    @TableField("team_id")
    private String teamId;

    /** 所属会话 id，用于运行详情按 thread 聚合。 */
    @TableField("thread_id")
    private String threadId;

    /** 所属 turn id，用于与工具调用、summary 串联。 */
    @TableField("turn_id")
    private String turnId;

    /** 用户可读标题。 */
    private String title;

    /** 团队整体目标。 */
    private String goal;

    /** 团队状态：pending、running、completed、failed。 */
    private String status;

    /** 执行时工作目录快照。 */
    private String cwd;

    /** 执行时沙箱模式快照，团队不允许自行提升。 */
    @TableField("sandbox_mode")
    private String sandboxMode;

    /** 是否已经通过运行前整体审批。 */
    private Integer approved;

    /** 是否已冻结成员和工具范围。 */
    private Integer frozen;

    /** supervisor 最多调度轮数。 */
    @TableField("max_rounds")
    private Integer maxRounds;

    /** 当前调度轮数。 */
    @TableField("current_round")
    private Integer currentRound;

    /** 当前或最近被调度成员。 */
    @TableField("current_agent")
    private String currentAgent;

    /** 团队短摘要，供右侧运行详情显示。 */
    private String summary;

    /** 失败原因；成功或运行中为空。 */
    @TableField("error_message")
    private String errorMessage;

    /** 创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;

    /** 更新时间，ISO-8601 文本。 */
    @TableField("updated_at")
    private String updatedAt;
}
