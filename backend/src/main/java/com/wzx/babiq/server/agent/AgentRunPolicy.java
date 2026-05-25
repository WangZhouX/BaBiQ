package com.wzx.babiq.server.agent;

import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.sandbox.SandboxMode;

/**
 * 单个 turn 的运行时权限快照。
 *
 * <p>设置页保存的是“下一轮默认值”，而 Agent 真正执行时必须使用 turn 启动时的快照。
 * 这个 record 把沙箱模式和审批策略一起传过 AgentLoop、ReActStrategy、RunnableConfig
 * 和工具拦截器，避免 UI 已切换但后端仍读取 yml 静态配置。</p>
 *
 * @param sandboxMode 本轮工具执行使用的沙箱模式；由 turn/start 从 AppSettings 快照生成
 * @param approvalPolicy 本轮工具调用使用的审批策略；NEVER 表示不安装 HITL hook
 */
public record AgentRunPolicy(SandboxMode sandboxMode, ApprovalPolicy approvalPolicy) {

    /**
     * 补齐空值默认值，保证旧单元测试入口或历史数据缺字段时仍按安全默认运行。
     *
     * @param sandboxMode 原始沙箱模式
     * @param approvalPolicy 原始审批策略
     */
    public AgentRunPolicy {
        if (sandboxMode == null) {
            sandboxMode = SandboxMode.WORKSPACE_WRITE;
        }
        if (approvalPolicy == null) {
            approvalPolicy = ApprovalPolicy.ON_REQUEST;
        }
    }

    /**
     * 使用强类型枚举创建运行时权限快照。
     *
     * @param sandboxMode 本轮沙箱模式
     * @param approvalPolicy 本轮审批策略
     * @return 运行时权限快照
     */
    public static AgentRunPolicy of(SandboxMode sandboxMode, ApprovalPolicy approvalPolicy) {
        return new AgentRunPolicy(sandboxMode, approvalPolicy);
    }

    /**
     * 从持久化字符串快照创建运行时权限。
     *
     * <p>旧数据可能为空或非法，此时回退到 yml 默认值；如果 yml 也不存在，则使用
     * WORKSPACE_WRITE + ON_REQUEST 这组兼容且偏安全的默认值。</p>
     *
     * @param sandboxMode 持久化的 SandboxMode 枚举名
     * @param approvalPolicy 持久化的 ApprovalPolicy 枚举名
     * @param defaults yml 默认配置
     * @return 运行时权限快照
     */
    public static AgentRunPolicy fromSnapshots(String sandboxMode, String approvalPolicy, AgentLoopProperties defaults) {
        return new AgentRunPolicy(parseSandboxMode(sandboxMode, defaults), parseApprovalPolicy(approvalPolicy, defaults));
    }

    /**
     * 从 yml 默认配置创建运行时权限，供兼容入口和缺失持久化快照时使用。
     *
     * @param defaults yml 默认配置
     * @return 运行时权限快照
     */
    public static AgentRunPolicy fromDefaults(AgentLoopProperties defaults) {
        return new AgentRunPolicy(
                defaults == null ? null : defaults.sandboxMode(),
                defaults == null ? null : defaults.approvalPolicy());
    }

    private static SandboxMode parseSandboxMode(String value, AgentLoopProperties defaults) {
        if (value != null && !value.isBlank()) {
            try {
                return SandboxMode.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                // 历史脏数据不能阻断审批恢复，下面统一回退到默认配置。
            }
        }
        return defaults == null ? null : defaults.sandboxMode();
    }

    private static ApprovalPolicy parseApprovalPolicy(String value, AgentLoopProperties defaults) {
        if (value != null && !value.isBlank()) {
            try {
                return ApprovalPolicy.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                // 历史脏数据不能阻断审批恢复，下面统一回退到默认配置。
            }
        }
        return defaults == null ? null : defaults.approvalPolicy();
    }
}
