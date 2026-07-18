package com.wzx.huitai.desktop.decision

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.port.ActionApproval
import com.wzx.huitai.action.port.ActionApprovalPort
import com.wzx.huitai.action.port.RiskEvaluation

/** 只把高风险审批请求翻译为 Compose 协调器等待，不执行动作、不持久化也不发送协议。 */
class ComposeApprovalPort(
    private val coordinator: ComposeActionDecisionCoordinator,
) : ActionApprovalPort {
    override suspend fun request(
        command: ActionCommand,
        preview: ActionPreview,
        riskEvaluation: RiskEvaluation,
        context: ActionContext,
    ): ActionApproval = coordinator.requestApproval(command, preview, riskEvaluation, context)
}
