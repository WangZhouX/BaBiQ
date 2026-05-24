package com.wzx.babiq.server.settings;

/**
 * BaBiQ 本地应用设置快照。
 *
 * <p>该 record 表示“下一轮 turn 默认会使用什么配置”。Turn 启动后会把这些值复制到
 * `bq_turns`，所以后续设置变化不会影响已经运行中的 turn。</p>
 *
 * @param activeProviderId 当前默认 Provider 标识
 * @param sandboxMode 当前默认沙箱模式
 * @param approvalPolicy 当前默认审批策略
 * @param defaultCwd 新建会话默认工作目录
 */
public record AppSettings(
        String activeProviderId,
        String sandboxMode,
        String approvalPolicy,
        String defaultCwd
) {
}
