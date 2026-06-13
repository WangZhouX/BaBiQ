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
 * `bq_orchestrations` 表对应的 MyBatis-Plus 实体。
 *
 * <p>该表保存 P6-2 一次流程编排的整体运行状态，节点明细放在
 * `bq_orchestration_nodes`，工具级明细继续复用 `bq_tool_calls`。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_orchestrations")
public class OrchestrationEntity {

    /** 数据库内部自增主键，不暴露给协议层。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层流程 id，以 orch_ 开头，由工具层生成并贯穿 UI 与运行记录。 */
    @TableField("orchestration_id")
    private String orchestrationId;

    /** 所属会话 id，用于运行详情按 thread 聚合。 */
    @TableField("thread_id")
    private String threadId;

    /** 所属 turn id，用于与工具调用、summary 串联。 */
    @TableField("turn_id")
    private String turnId;

    /** 用户可读标题。 */
    private String title;

    /** 拓扑类型：sequential、parallel 或 routing。 */
    private String topology;

    /** 流程状态：pending、running、completed、failed。 */
    private String status;

    /** 执行时工作目录快照。 */
    private String cwd;

    /** 执行时沙箱模式快照，流程不允许自行提升。 */
    @TableField("sandbox_mode")
    private String sandboxMode;

    /** 是否已经通过运行前整体审批。 */
    private Integer approved;

    /** 是否已冻结节点和工具范围。 */
    private Integer frozen;

    /** 画布编排结构树 JSON；为空时表示旧版平铺 topology。 */
    @TableField("structure_json")
    private String structureJson;

    /** 流程短摘要，供右侧运行详情显示。 */
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
