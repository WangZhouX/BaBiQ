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
 * `bq_team_messages` 表对应的 MyBatis-Plus 实体。
 *
 * <p>团队消息时间线保留 supervisor 路由、成员摘要和用户直发 teammate 的结构化记录，
 * 既服务 UI，也保留调试和审计证据。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_team_messages")
public class TeamMessageEntity {

    /** 数据库内部自增主键，不暴露给协议层。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属团队 id。 */
    @TableField("team_id")
    private String teamId;

    /** 协议层消息 id，用于 UI 去重。 */
    @TableField("message_id")
    private String messageId;

    /** 所属会话 id。 */
    @TableField("thread_id")
    private String threadId;

    /** 所属 turn id；手动直发消息可为空。 */
    @TableField("turn_id")
    private String turnId;

    /** 发送方：user、supervisor 或成员名。 */
    @TableField("from_agent")
    private String fromAgent;

    /** 接收方：supervisor、成员名或 all。 */
    @TableField("to_agent")
    private String toAgent;

    /** 消息类型：route、member_summary、direct_user、system。 */
    @TableField("message_type")
    private String messageType;

    /** 消息正文或短摘要。 */
    private String content;

    /** supervisor 路由结构化决策 JSON；非路由消息为空。 */
    @TableField("route_decision_json")
    private String routeDecisionJson;

    /** 该消息所属调度轮数。 */
    private Integer round;

    /** 创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;
}
