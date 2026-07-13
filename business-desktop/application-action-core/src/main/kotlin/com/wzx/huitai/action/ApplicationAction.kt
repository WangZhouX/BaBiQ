package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionResult

/**
 * 动作执行时冻结的页面与身份上下文。
 *
 * @param identityScope 创建动作时绑定的完整身份范围。
 * @param pageId 创建动作时所在页面标识。
 * @param contextRevision 创建动作时的页面版本。
 * @param permissions 创建动作时冻结的权限集合。
 */
data class ActionContext(
    val identityScope: ActionIdentityScope,
    val pageId: String,
    val contextRevision: Long,
    val permissions: Set<String>,
) {
    /** 日志只暴露页面版本和权限数量，不暴露身份或权限明细。 */
    override fun toString(): String =
        "ActionContext(pageId=$pageId, contextRevision=$contextRevision, permissions=${permissions.size}, " +
            "identityScope=[REDACTED])"
}

/** 动作对账结果，与正常执行结果保持独立语义。 */
sealed interface ReconciliationResult {
    /** 当前动作不支持自动对账。 */
    data object Unsupported : ReconciliationResult

    /** 对账确认远程动作已成功。 */
    data class Succeeded(val remoteReference: String? = null) : ReconciliationResult {
        override fun toString(): String = "ReconciliationResult.Succeeded(remoteReference=[REDACTED])"
    }

    /** 对账确认远程动作已失败。 */
    data class Failed(val error: ActionError) : ReconciliationResult {
        override fun toString(): String = "ReconciliationResult.Failed(errorCode=${error.code})"
    }
}

/**
 * 强类型应用动作。
 *
 * @param I 解码后的动作输入类型。
 * @param O 动作成功输出类型。
 */
interface ApplicationAction<I : Any, O : Any> {
    val descriptor: ActionDescriptor

    /** 生成无副作用预览。 */
    suspend fun preview(input: I, context: ActionContext): ActionPreview

    /** 执行已通过风险流程的动作。 */
    suspend fun execute(input: I, context: ActionContext): ActionResult<O>

    /** 对结果不确定的远程动作执行查询或对账。 */
    suspend fun reconcile(
        input: I,
        context: ActionContext,
        remoteReference: String?,
    ): ReconciliationResult = ReconciliationResult.Unsupported
}
