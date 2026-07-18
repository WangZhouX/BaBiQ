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
import com.wzx.huitai.demo.model.DemoFormState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 保存草稿动作的强类型输入。 */
data class DemoSaveDraftInput(val executionId: String)

/** 只调用 FakeHuitaiGateway 保存当前不可变状态。 */
class DemoSaveDraftAction private constructor(
    private val screen: DemoScreenModel,
    private val gateway: FakeHuitaiGateway,
) : ApplicationAction<DemoSaveDraftInput, JsonObject> {
    override val descriptor = demoDescriptor(
        id = "demo.save_draft",
        title = "保存草稿",
        description = "按 executionId 幂等保存通用演示草稿",
        risk = ActionRiskLevel.REVERSIBLE_WRITE,
        replay = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
        reconciliation = ReconciliationPolicy.QUERY_REMOTE,
        requiredPermissions = setOf("demo.write"),
        inputSchema = strictSchema("executionId" to "string"),
    )

    /** 保存预览只读取 revision，不调用远端。 */
    override suspend fun preview(input: DemoSaveDraftInput, context: ActionContext): ActionPreview =
        ActionPreview(input.executionId, "保存 revision ${screen.readWithRevision().revision} 的草稿")

    /** 把当前状态交给假远端一次。 */
    override suspend fun execute(input: DemoSaveDraftInput, context: ActionContext): ActionResult<JsonObject> {
        val snapshot = screen.readWithRevision()
        if (context.pageId != DemoFormState.PAGE_ID || snapshot.revision != context.contextRevision) {
            return ActionResult.Failure(
                input.executionId,
                ActionError(ActionErrorCode.CONTEXT_STALE, "草稿页面上下文已变化"),
            )
        }
        return when (val result = gateway.saveDraft(input.executionId, snapshot)) {
            is FakeGatewayResult.Confirmed -> ActionResult.Success(
                executionId = input.executionId,
                output = buildJsonObject { put("saved", true) },
                remoteReference = result.remoteReference,
            )
            is FakeGatewayResult.SentButResponseLost -> ActionResult.OutcomeUnknown(
                executionId = input.executionId,
                error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "草稿响应丢失"),
                remoteReference = result.remoteReference,
                reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            )
        }
    }

    /** 只按 executionId 查询假远端事实，不回退到 execute。 */
    override suspend fun reconcile(
        input: DemoSaveDraftInput,
        context: ActionContext,
        remoteReference: String?,
        executionId: String,
    ): ReconciliationResult = gateway.queryDraft(executionId)?.let { record ->
        ReconciliationResult.Succeeded(record.remoteReference, executionId)
    } ?: ReconciliationResult.NotFound(executionId)

    companion object {
        /** 创建绑定页面模型和假网关的注册项。 */
        fun registered(
            screen: DemoScreenModel,
            gateway: FakeHuitaiGateway,
        ): RegisteredAction<DemoSaveDraftInput, JsonObject> = RegisteredAction(
            DemoSaveDraftAction(screen, gateway),
            executionOnlyCodec(::DemoSaveDraftInput),
            DEMO_JSON_OUTPUT_CODEC,
        )
    }
}
