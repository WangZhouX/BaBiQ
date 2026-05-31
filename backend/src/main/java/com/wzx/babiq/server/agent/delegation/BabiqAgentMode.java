package com.wzx.babiq.server.agent.delegation;

/**
 * BaBiQ 子 Agent 委派模式。
 *
 * <p>P6-1 只开放只读工具委派，写类委派需要 asNode/HITL 恢复链路，不能混在本阶段。</p>
 */
public enum BabiqAgentMode {

    /** 子 Agent 只能使用只读工具，写文件、执行命令和补丁类能力由工具白名单与沙箱双重拦截。 */
    READ_ONLY_TOOL
}
