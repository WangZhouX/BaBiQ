package com.wzx.huitai.integration.identity

import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionResult
import kotlinx.serialization.json.JsonElement

/**
 * 租户或用户身份变化时，协调旧身份动作的最小边界端口。
 *
 * 动作存储和 Agent 适配器实现此端口；集成核心不感知其具体实现。
 */
interface IdentityBoundaryActionPort {
    /** 取消旧身份下尚未产生远程副作用的指定状态动作。 */
    suspend fun cancelPreExecution(
        identityScope: ActionIdentityScope,
        states: Set<ActionExecutionState>,
    )

    /** 将旧身份下已经执行的远程写入脱离当前上下文并交给旧 scope 对账。 */
    suspend fun detachExecutingForReconciliation(identityScope: ActionIdentityScope)

    /** 仅按调用方提供的完整身份 scope 查询精确动作结果。 */
    suspend fun result(
        executionId: String,
        identityScope: ActionIdentityScope,
    ): ActionResult<JsonElement>?
}
