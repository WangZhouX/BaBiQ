package com.wzx.huitai.desktop.decision

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.port.ActionConfirmation
import com.wzx.huitai.action.port.ActionConfirmationPort

/** 只把 ActionBus 确认请求翻译为 Compose 协调器等待，不执行动作、不持久化也不发送协议。 */
class ComposeConfirmationPort(
    private val coordinator: ComposeActionDecisionCoordinator,
) : ActionConfirmationPort {
    override suspend fun request(
        command: ActionCommand,
        preview: ActionPreview,
        context: ActionContext,
    ): ActionConfirmation = coordinator.requestConfirmation(command, preview, context)
}
