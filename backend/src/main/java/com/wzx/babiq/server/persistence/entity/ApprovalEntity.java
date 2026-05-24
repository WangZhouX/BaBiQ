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
 * `bq_approvals` 表对应的 MyBatis-Plus 实体。
 *
 * <p>P2-1 只建立审批持久化结构，P2-3 会把权限策略和 UI 决策接入这里。提前保留该实体，
 * 可以让后续审批恢复和审计沿用同一个 repository 边界。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_approvals")
public class ApprovalEntity {

    /** 数据库内部自增主键；不暴露给桌面端。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层 approvalId；桌面端响应审批时带回该值。 */
    @TableField("approval_id")
    private String approvalId;

    /** 所属 threadId；用于按会话查询审批历史。 */
    @TableField("thread_id")
    private String threadId;

    /** 所属 turnId；用于恢复等待中的审批。 */
    @TableField("turn_id")
    private String turnId;

    /** 请求执行的工具名。 */
    @TableField("tool_name")
    private String toolName;

    /** 工具原始参数 JSON；展示和执行前都必须按不可信输入处理。 */
    @TableField("args_json")
    private String argsJson;

    /** 用户编辑后的参数 JSON；没有编辑时为空。 */
    @TableField("edited_args_json")
    private String editedArgsJson;

    /** 用户决策，例如 approve、deny、always、edit。 */
    private String decision;

    /** 决策生效范围，例如 once、session、workspace。 */
    private String scope;

    /** 审批状态，例如 pending、resolved、expired。 */
    private String status;

    /** 审批请求创建时间。 */
    @TableField("created_at")
    private String createdAt;

    /** 审批完成时间；等待用户时为空。 */
    @TableField("resolved_at")
    private String resolvedAt;
}
