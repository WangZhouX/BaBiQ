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
 * `bq_team_artifacts` 表对应的 MyBatis-Plus 实体。
 *
 * <p>团队记忆产物是单次团队运行的任务级 blackboard 记录，和用户级长期记忆分离。
 * SQLite 记录是事实源，Markdown 文件是可读镜像。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_team_artifacts")
public class TeamArtifactEntity {

    /** 数据库内部自增主键，不暴露给协议层。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属团队 id，对应 bq_teams.team_id。 */
    @TableField("team_id")
    private String teamId;

    /** 协议层产物 id，以 teamart_ 开头。 */
    @TableField("artifact_id")
    private String artifactId;

    /** 产物类型：TEAM_INDEX、MEMBER_OUTPUT、DIGEST、RESULT。 */
    @TableField("artifact_type")
    private String artifactType;

    /** Markdown 镜像路径，相对团队目录保存。 */
    @TableField("relative_path")
    private String relativePath;

    /** 文件内容 SHA-256，用于判断镜像漂移。 */
    private String sha256;

    /** 文本 token 粗估值，不用于计费。 */
    @TableField("token_estimate")
    private Integer tokenEstimate;

    /** 产物所属调度轮；非轮次产物为 0。 */
    private Integer round;

    /** 成员名；非成员产物为空。 */
    @TableField("member_name")
    private String memberName;

    /** Markdown 正文副本，便于后续搜索检索无损追加。 */
    private String content;

    /** 创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;

    /** 更新时间，ISO-8601 文本。 */
    @TableField("updated_at")
    private String updatedAt;
}
