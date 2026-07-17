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
 * `bq_tool_calls` 表对应的 MyBatis-Plus 实体。
 *
 * <p>工具调用记录是 P2-4 运行详情的核心数据源。它和 `bq_items` 不同：item 负责用户可见聊天流，
 * tool call 负责审计每次工具动作，即使工具结果没有单独渲染成 item，也能在这里追踪。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_tool_calls")
public class ToolCallEntity {

    /** 数据库内部自增主键；不暴露给协议层。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** SAA 工具调用 id；用于幂等更新同一次工具调用的完成状态。 */
    @TableField("tool_call_id")
    private String toolCallId;

    /** 所属 threadId，便于按会话聚合运行详情。 */
    @TableField("thread_id")
    private String threadId;

    /** 所属 turnId，运行详情按 turn 回放时使用。 */
    @TableField("turn_id")
    private String turnId;

    /** 工具名，例如 read_file、write_file、exec_shell。 */
    @TableField("tool_name")
    private String toolName;

    /** 实际执行工具的 Agent 名称；主 Agent 默认为 babiq_agent，子 Agent 调用时为 explorer 等名称。 */
    @TableField("agent_name")
    private String agentName;

    /** 委派来源的父 Agent 名称；主 Agent 直接调用工具时为空。 */
    @TableField("parent_agent_name")
    private String parentAgentName;

    /** 子 Agent 委派 id；非委派工具调用为空，用于把运行记录和 agentDelegation item 串起来。 */
    @TableField("delegation_id")
    private String delegationId;

    /** application_action 生成的 executionId；其他工具调用为空。 */
    @TableField("execution_id")
    private String executionId;

    @TableField("desktop_instance_id") private String desktopInstanceId;
    @TableField("desktop_session_id") private String desktopSessionId;
    @TableField("auth_session_id") private String authSessionId;
    @TableField("identity_epoch") private Long identityEpoch;
    @TableField("user_id") private String userId;
    @TableField("tenant_id") private String tenantId;
    @TableField("platform_id") private String platformId;

    /** 工具原始参数 JSON；读取和展示时都必须视为不可信数据。 */
    @TableField("args_json")
    private String argsJson;

    /** 工具状态，running、completed、failed 或 denied。 */
    private String status;

    /** 工具结果短预览，避免把超大输出完整放入运行详情列表。 */
    @TableField("result_preview")
    private String resultPreview;

    /** 错误或拒绝原因；成功时为空。 */
    @TableField("error_message")
    private String errorMessage;

    /** 工具开始时间。 */
    @TableField("started_at")
    private String startedAt;

    /** 工具完成时间；运行中为空。 */
    @TableField("completed_at")
    private String completedAt;
}
