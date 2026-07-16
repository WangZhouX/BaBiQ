package com.wzx.huitai.agent.client

/**
 * Agent 连接的确定性退避策略。
 *
 * 第一次连续失败等待 1 秒，随后按 2、4、8 秒增长并在 10 秒封顶；第十次连续失败不再
 * 自动重试，由 Supervisor 进入人工重试状态。
 */
class AgentReconnectPolicy {
    /**
     * 返回指定连续失败次数之后的等待毫秒数；返回 `null` 表示必须停止自动重试。
     *
     * @param consecutiveFailures 从 1 开始的连续失败次数，连接成功后由上层重置。
     */
    fun retryDelayMillis(consecutiveFailures: Int): Long? {
        require(consecutiveFailures > 0) { "consecutiveFailures must be positive" }
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return null
        return DELAYS_MILLIS[(consecutiveFailures - 1).coerceAtMost(DELAYS_MILLIS.lastIndex)]
    }

    companion object {
        /** 进入人工重试前允许的连续瞬时失败总数。 */
        const val MAX_CONSECUTIVE_FAILURES: Int = 10

        private val DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L)
    }
}
