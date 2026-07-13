package com.wzx.huitai.action.port

import java.time.Instant

/** 为动作核心提供可测试的当前时间。 */
fun interface ActionClock {
    /** 返回当前瞬时时间。 */
    fun now(): Instant
}
