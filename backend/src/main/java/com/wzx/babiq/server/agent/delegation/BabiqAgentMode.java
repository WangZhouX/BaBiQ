package com.wzx.babiq.server.agent.delegation;

/**
 * BaBiQ 子 Agent 委派模式。
 *
 * <p>P6-1 只开放只读工具委派；P6-2 在“运行前整体审批一次”的前提下，
 * 允许流程节点继承当前 turn 的工作区写权限，但最终边界仍由 BaBiQ 沙箱裁决。</p>
 */
public enum BabiqAgentMode {

    /** 子 Agent 只能使用只读工具，写文件、执行命令和补丁类能力由工具白名单与沙箱双重拦截。 */
    READ_ONLY_TOOL,

    /**
     * 流程节点可以使用当前工作区写类工具。
     *
     * <p>该模式只表达节点角色，不提升沙箱。工具实际写入仍受本轮 {@code SandboxMode}
     * 和工作目录/可写根目录限制，适用于 P6-2 approve-once 的冻结流程。</p>
     */
    WORKSPACE_TOOL
}
