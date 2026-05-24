package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * `bq_threads` 表对应的 MyBatis-Plus 实体。
 *
 * <p>Thread 是 BaBiQ 的“会话线程”，不是 Java 线程。它承载工作目录、默认模型、沙箱和审批策略快照，
 * P2-2 的历史列表、会话恢复和最近对话都会从这张表读取基础信息。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_threads")
public class ThreadEntity {

    /** 数据库内部自增主键；只给 SQLite 和 MyBatis-Plus 使用，不暴露到 JSON-RPC。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层 threadId；桌面端、后端运行期和历史接口都使用它定位会话。 */
    @TableField("thread_id")
    private String threadId;

    /** 会话标题；默认可由用户首条输入生成，后续设置页或历史页可修改。 */
    private String title;

    /** 会话绑定的工作目录；最近列表会按该值区分不同项目。 */
    private String cwd;

    /** 默认 Provider 标识；新 turn 没有显式选择时从这里继承。 */
    @TableField("provider_id")
    private String providerId;

    /** 默认模型名；和 providerId 一起构成本会话的模型快照。 */
    private String model;

    /** 默认沙箱模式；后续 turn 会把该值复制为运行时快照。 */
    @TableField("sandbox_mode")
    private String sandboxMode;

    /** 默认审批策略；后续 turn 会把该值复制为运行时快照。 */
    @TableField("approval_policy")
    private String approvalPolicy;

    /** 会话状态；active 表示默认可见，archived 表示软归档。 */
    private String status;

    /** 创建时间，使用 Instant 字符串保存，避免 SQLite 时区转换歧义。 */
    @TableField("created_at")
    private String createdAt;

    /** 最近更新时间；列表按它倒序展示。 */
    @TableField("updated_at")
    private String updatedAt;

    /** 软归档时间；为空代表会话仍在默认最近列表中显示。 */
    @TableField("archived_at")
    private String archivedAt;

    /**
     * 创建一个默认可见的会话实体。
     *
     * @param threadId 协议层会话标识
     * @param title 会话标题
     * @param cwd 工作目录
     * @param providerId Provider 标识
     * @param model 模型名
     * @param sandboxMode 沙箱模式
     * @param approvalPolicy 审批策略
     * @param now 创建和更新时间
     * @return 可直接插入数据库的 thread 实体
     */
    public static ThreadEntity active(
            String threadId,
            String title,
            String cwd,
            String providerId,
            String model,
            String sandboxMode,
            String approvalPolicy,
            Instant now) {
        ThreadEntity entity = new ThreadEntity();
        String timestamp = now.toString();
        entity.setThreadId(threadId);
        entity.setTitle(title);
        entity.setCwd(cwd);
        entity.setProviderId(providerId);
        entity.setModel(model);
        entity.setSandboxMode(sandboxMode);
        entity.setApprovalPolicy(approvalPolicy);
        entity.setStatus("active");
        entity.setCreatedAt(timestamp);
        entity.setUpdatedAt(timestamp);
        return entity;
    }
}
