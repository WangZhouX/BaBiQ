package com.wzx.huitai.presentation.screen

/**
 * 由当前状态和事件确定性计算下一状态的纯 reducer。
 *
 * 实现不得执行网络、持久化或 Agent 调用，也不得修改传入状态。
 */
fun interface ScreenReducer<S : Any, E : Any> {
    /** 返回新的不可变状态。 */
    fun reduce(state: S, event: E): S
}
