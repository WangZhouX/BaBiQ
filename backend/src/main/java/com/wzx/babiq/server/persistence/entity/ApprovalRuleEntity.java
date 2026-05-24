package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * `bq_approval_rules` 表实体。
 *
 * <p>该表只保存 session scope 的 always 规则。每条规则必须同时绑定 thread、tool 和参数指纹，
 * 避免用户点一次“始终允许”后把所有未来工具调用都放开。</p>
 */
@TableName("bq_approval_rules")
public class ApprovalRuleEntity {

    /** 数据库内部主键，自增，不暴露给协议层。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 规则业务 ID，用于日志和排查。 */
    @TableField("rule_id")
    private String ruleId;

    /** 规则作用域，P2-3 只允许 session。 */
    @TableField("scope")
    private String scope;

    /** session scope 绑定的 threadId。 */
    @TableField("thread_id")
    private String threadId;

    /** workspace scope 预留工作目录；P2-3 不启用。 */
    @TableField("cwd")
    private String cwd;

    /** 工具名，例如 write_file。 */
    @TableField("tool_name")
    private String toolName;

    /** 工具参数指纹，避免宽泛放行。 */
    @TableField("args_fingerprint")
    private String argsFingerprint;

    /** 决策类型，P2-3 固定 always。 */
    @TableField("decision")
    private String decision;

    /** 过期时间，P2-3 默认为空，由 session 清理失效。 */
    @TableField("expires_at")
    private String expiresAt;

    /** 创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getThreadId() {
        return threadId;
    }

    public void setThreadId(String threadId) {
        this.threadId = threadId;
    }

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getArgsFingerprint() {
        return argsFingerprint;
    }

    public void setArgsFingerprint(String argsFingerprint) {
        this.argsFingerprint = argsFingerprint;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
