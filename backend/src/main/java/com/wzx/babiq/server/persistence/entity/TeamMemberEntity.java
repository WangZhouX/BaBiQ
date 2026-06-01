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
 * `bq_team_members` 表对应的 MyBatis-Plus 实体。
 *
 * <p>每行表示一次团队协作中的一个成员状态。成员内部工具调用仍由
 * `bq_tool_calls.delegation_id` 记录，这里只保存右侧面板所需的聚合视图。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_team_members")
public class TeamMemberEntity {

    /** 数据库内部自增主键，不暴露给协议层。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属团队 id。 */
    @TableField("team_id")
    private String teamId;

    /** 协议层成员 id。 */
    @TableField("member_id")
    private String memberId;

    /** 成员 ASCII 技术名。 */
    private String name;

    /** 桌面端展示名。 */
    @TableField("display_name")
    private String displayName;

    /** 成员角色，例如 explorer、writer、reviewer。 */
    private String role;

    /** 成员委派模式：READ_ONLY_TOOL 或 WORKSPACE_TOOL。 */
    private String mode;

    /** 成员工具白名单，逗号分隔，仅用于运行详情摘要。 */
    @TableField("tool_names")
    private String toolNames;

    /** 成员状态：pending、running、completed、failed。 */
    private String status;

    /** 成员排序号，保证 UI 稳定展示。 */
    @TableField("member_order")
    private Integer memberOrder;

    /** 成员聚合工具调用次数。 */
    @TableField("tool_call_count")
    private Integer toolCallCount;

    /** 成员 token 粗估值，不用于计费。 */
    @TableField("token_estimate")
    private Integer tokenEstimate;

    /** 成员短摘要。 */
    private String summary;

    /** 创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;

    /** 更新时间，ISO-8601 文本。 */
    @TableField("updated_at")
    private String updatedAt;
}
