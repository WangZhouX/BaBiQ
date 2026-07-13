package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode

/** 在调用动作实现前校验命令与冻结上下文仍然一致。 */
class ActionExecutionContextValidator {
    /** 返回 null 表示校验通过，否则返回不含敏感身份值的结构化错误。 */
    fun validate(
        descriptor: ActionDescriptor,
        command: ActionCommand,
        context: ActionContext,
    ): ActionError? = when {
        command.actionId != descriptor.id ->
            ActionError(ActionErrorCode.PROTOCOL_ERROR, "命令动作标识与描述符不一致")
        command.pageId != context.pageId ->
            ActionError(ActionErrorCode.CONTEXT_STALE, "命令页面已变化")
        command.contextRevision != context.contextRevision ->
            ActionError(ActionErrorCode.CONTEXT_STALE, "页面上下文版本已变化")
        command.identityScope != context.identityScope ->
            ActionError(ActionErrorCode.CONTEXT_STALE, "业务身份上下文已变化")
        !context.permissions.containsAll(descriptor.requiredPermissions) ->
            ActionError(ActionErrorCode.PERMISSION_DENIED, "当前身份缺少动作权限")
        else -> null
    }
}
