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
 * `bq_turns` 表对应的 MyBatis-Plus 实体。
 *
 * <p>Turn 表示一次用户输入到 Agent 完成之间的完整运行回合。它把输入文本、模型选择、沙箱模式、
 * 审批策略和失败原因都保存为快照，避免用户后续修改设置后影响历史解释。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_turns")
public class TurnEntity {

    /** 数据库内部自增主键；只用于持久化层，不暴露到协议。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层 turnId；item、审批、运行摘要都通过它关联同一轮运行。 */
    @TableField("turn_id")
    private String turnId;

    /** 所属 threadId；指向 `bq_threads.thread_id`。 */
    @TableField("thread_id")
    private String threadId;

    /** 运行状态，例如 RUNNING、COMPLETED、FAILED。 */
    private String status;

    /** 用户本轮输入文本；用于历史恢复、调试和后续摘要生成。 */
    @TableField("input_text")
    private String inputText;

    /** 本轮实际工作目录快照。 */
    private String cwd;

    /** 本轮实际 Provider 标识快照。 */
    @TableField("provider_id")
    private String providerId;

    /** 本轮实际模型名快照。 */
    private String model;

    /** 本轮实际沙箱模式快照。 */
    @TableField("sandbox_mode")
    private String sandboxMode;

    /** 本轮实际审批策略快照。 */
    @TableField("approval_policy")
    private String approvalPolicy;

    /** turn 开始时间，使用 Instant 字符串保存。 */
    @TableField("started_at")
    private String startedAt;

    /** turn 完成时间；运行中或异常中断时为空。 */
    @TableField("completed_at")
    private String completedAt;

    /** 失败原因；仅失败或恢复诊断时写入。 */
    @TableField("failure_reason")
    private String failureReason;

    /** 恢复原因；服务端启动时把遗留非终态 turn 收口后写入，方便运行记录解释。 */
    @TableField("recovery_reason")
    private String recoveryReason;

    /** 恢复收口时间；为空表示该 turn 不是由启动恢复流程关闭的。 */
    @TableField("recovered_at")
    private String recoveredAt;

    /** 取消原因；用户取消或主动中断时写入，和失败原因区分开。 */
    @TableField("cancel_reason")
    private String cancelReason;

    @TableField("desktop_instance_id")
    private String desktopInstanceId;

    @TableField("desktop_session_id")
    private String desktopSessionId;

    @TableField("auth_session_id")
    private String authSessionId;

    @TableField("identity_epoch")
    private Long identityEpoch;

    @TableField("user_id")
    private String userId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("platform_id")
    private String platformId;
}
