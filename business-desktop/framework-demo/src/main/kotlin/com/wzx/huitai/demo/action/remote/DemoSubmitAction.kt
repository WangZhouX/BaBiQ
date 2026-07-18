package com.wzx.huitai.demo.action.remote

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.ApplicationAction
import com.wzx.huitai.action.ReconciliationResult
import com.wzx.huitai.action.RegisteredAction
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.demo.action.DEMO_JSON_OUTPUT_CODEC
import com.wzx.huitai.demo.action.demoDescriptor
import com.wzx.huitai.demo.action.executionOnlyCodec
import com.wzx.huitai.demo.action.strictSchema
import com.wzx.huitai.demo.gateway.FakeGatewayResult
import com.wzx.huitai.demo.gateway.FakeHuitaiGateway
import com.wzx.huitai.demo.model.DemoScreenModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 提交动作的强类型输入。 */
data class DemoSubmitInput(val executionId: String)

/** 高风险提交动作；结果不确定后只能按 executionId 对账。 */
class DemoSubmitAction private constructor(
    private val screen: DemoScreenModel,
    private val gateway: FakeHuitaiGateway,
) : ApplicationAction<DemoSubmitInput, JsonObject> {
    override val descriptor = demoDescriptor(
        id = "demo.submit",
        title = "提交资料",
        description = "提交通用演示资料且禁止自动 execute 重放",
        risk = ActionRiskLevel.HIGH_RISK,
        replay = ActionReplayPolicy.NEVER,
        reconciliation = ReconciliationPolicy.QUERY_REMOTE,
        requiredPermissions = setOf("demo.submit"),
        inputSchema = strictSchema("executionId" to "string"),
    )

    /** 提交预览只读取 revision，不调用远端。 */
    override suspend fun preview(input: DemoSubmitInput, context: ActionContext): ActionPreview =
        ActionPreview(
            executionId = input.executionId,
            summary = "提交 revision ${screen.state.value.revision} 的资料",
            warnings = listOf("提交后必须以远端事实为准"),
        )

    /** 调用假远端一次，并把发送后响应丢失显式收口为 OUTCOME_UNKNOWN。 */
    override suspend fun execute(input: DemoSubmitInput, context: ActionContext): ActionResult<JsonObject> =
        when (val result = gateway.submit(input.executionId, screen.state.value)) {
            is FakeGatewayResult.Confirmed -> ActionResult.Success(
                executionId = input.executionId,
                output = buildJsonObject { put("submitted", true) },
                remoteReference = result.remoteReference,
            )
            is FakeGatewayResult.SentButResponseLost -> ActionResult.OutcomeUnknown(
                executionId = input.executionId,
                error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "提交响应丢失"),
                remoteReference = result.remoteReference,
                reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            )
        }

    /** 结果不确定后只查询远端提交事实，绝不再次调用 submit。 */
    override suspend fun reconcile(
        input: DemoSubmitInput,
        context: ActionContext,
        remoteReference: String?,
        executionId: String,
    ): ReconciliationResult = gateway.querySubmission(executionId)?.let { record ->
        ReconciliationResult.Succeeded(record.remoteReference, executionId)
    } ?: ReconciliationResult.NotFound(executionId)

    companion object {
        /** 创建绑定页面模型和假网关的注册项。 */
        fun registered(
            screen: DemoScreenModel,
            gateway: FakeHuitaiGateway,
        ): RegisteredAction<DemoSubmitInput, JsonObject> = RegisteredAction(
            DemoSubmitAction(screen, gateway),
            executionOnlyCodec(::DemoSubmitInput),
            DEMO_JSON_OUTPUT_CODEC,
        )
    }
}
