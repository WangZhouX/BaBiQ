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
 * `bq_orchestration_nodes` 表对应的 MyBatis-Plus 实体。
 *
 * <p>每行表示一次流程运行里的一个节点状态。节点内部工具调用仍由
 * `bq_tool_calls.delegation_id` 记录，这里只保存聚合视图。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_orchestration_nodes")
public class OrchestrationNodeEntity {

    /** 数据库内部自增主键，不暴露给协议层。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属流程 id。 */
    @TableField("orchestration_id")
    private String orchestrationId;

    /** 协议层节点 id。 */
    @TableField("node_id")
    private String nodeId;

    /** 节点 ASCII 技术名。 */
    private String name;

    /** 桌面端展示名。 */
    @TableField("display_name")
    private String displayName;

    /** 节点委派模式：READ_ONLY_TOOL 或 WORKSPACE_TOOL。 */
    private String mode;

    /** 节点工具白名单，逗号分隔，仅用于运行详情摘要。 */
    @TableField("tool_names")
    private String toolNames;

    /** 节点状态：pending、running、completed、failed。 */
    private String status;

    /** 节点排序号，保证 UI 稳定展示。 */
    @TableField("node_order")
    private Integer nodeOrder;

    /** 节点聚合工具调用次数。 */
    @TableField("tool_call_count")
    private Integer toolCallCount;

    /** 节点 token 粗估值，不用于计费。 */
    @TableField("token_estimate")
    private Integer tokenEstimate;

    /** 节点短摘要。 */
    private String summary;

    /** 创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;

    /** 更新时间，ISO-8601 文本。 */
    @TableField("updated_at")
    private String updatedAt;
}
