package com.wzx.huitai.presentation.screen

import com.wzx.huitai.presentation.context.PageContextSnapshot

/** 可从当前不可变页面状态生成 Agent 可见页面事实的页面。 */
interface AgentAwareScreen {
    /** 从调用时的同一份页面状态生成上下文快照。 */
    fun pageContext(): PageContextSnapshot
}
