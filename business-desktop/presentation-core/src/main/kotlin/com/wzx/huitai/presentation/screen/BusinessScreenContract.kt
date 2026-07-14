package com.wzx.huitai.presentation.screen

import kotlinx.coroutines.flow.StateFlow

/**
 * 业务页面的状态与事件入口。
 *
 * @param S 不可变页面状态类型。
 * @param E 页面事件类型；副作用应由页面外部的 Controller 或 UseCase 处理。
 */
interface BusinessScreenContract<S : Any, E : Any> {
    /** 当前页面状态的只读流。 */
    val state: StateFlow<S>

    /** 将页面事件交给 reducer 计算下一状态。 */
    fun dispatch(event: E)
}
