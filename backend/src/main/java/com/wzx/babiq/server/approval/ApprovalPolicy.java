package com.wzx.babiq.server.approval;

/**
 * 审批策略。
 */
public enum ApprovalPolicy {
    /** 不安装 HITL 审批 Hook，工具仍然受沙箱、拦截器和工具自身校验约束。 */
    NEVER,
    /** 所有当前可见工具调用都先进入人工确认，用于高风险调试或演示场景。 */
    ALWAYS,
    /** 只对写文件、执行命令、补丁、MCP 等高风险工具请求人工确认。 */
    ON_REQUEST,
    /** 预留策略：后续可用于失败后再请求人工介入，目前按按需询问处理。 */
    ON_FAILURE
}
