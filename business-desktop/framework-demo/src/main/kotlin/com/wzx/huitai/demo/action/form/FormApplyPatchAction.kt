package com.wzx.huitai.demo.action.form

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.ActionInputCodec
import com.wzx.huitai.action.ApplicationAction
import com.wzx.huitai.action.RegisteredAction
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionPreviewChange
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.demo.action.DEMO_JSON_OUTPUT_CODEC
import com.wzx.huitai.demo.action.decodeStrict
import com.wzx.huitai.demo.action.demoDescriptor
import com.wzx.huitai.demo.action.requiredPatch
import com.wzx.huitai.demo.action.requiredString
import com.wzx.huitai.demo.action.strictSchema
import com.wzx.huitai.demo.model.DemoFormEvent
import com.wzx.huitai.demo.model.DemoScreenModel
import com.wzx.huitai.presentation.form.FormPatch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 表单补丁应用动作的强类型输入。 */
data class FormApplyPatchInput(val executionId: String, val patch: FormPatch)

/** 通过 DemoFormEvent 原子应用一个绑定 revision 的补丁。 */
class FormApplyPatchAction private constructor(
    private val screen: DemoScreenModel,
) : ApplicationAction<FormApplyPatchInput, JsonObject> {
    override val descriptor = demoDescriptor(
        id = "form.apply_patch",
        title = "应用表单补丁",
        description = "确认后应用通用七字段补丁",
        risk = ActionRiskLevel.REVERSIBLE_WRITE,
        replay = ActionReplayPolicy.SAFE,
        requiredPermissions = setOf("demo.write"),
        inputSchema = strictSchema("executionId" to "string", "patch" to "object"),
    )

    /** 根据补丁生成确认预览，不修改页面。 */
    override suspend fun preview(input: FormApplyPatchInput, context: ActionContext): ActionPreview = ActionPreview(
        executionId = input.executionId,
        summary = "应用 ${input.patch.changes.size} 项字段变化",
        changes = input.patch.changes.map { change ->
            ActionPreviewChange(change.fieldId, change.previousValue, change.newValue)
        },
    )

    /** 只派发强类型补丁事件，并验证 reducer 确实完成一次版本更新。 */
    override suspend fun execute(input: FormApplyPatchInput, context: ActionContext): ActionResult<JsonObject> {
        val before = screen.state.value
        screen.dispatch(DemoFormEvent.ApplyPatch(input.patch))
        val after = screen.state.value
        if (after.revision != before.revision + 1) {
            return ActionResult.Failure(
                input.executionId,
                ActionError(ActionErrorCode.CONTEXT_STALE, "表单补丁未能应用"),
            )
        }
        return ActionResult.Success(
            input.executionId,
            buildJsonObject {
                put("revision", after.revision)
                put("changeCount", input.patch.changes.size)
            },
        )
    }

    companion object {
        private val INPUT_CODEC = ActionInputCodec<FormApplyPatchInput> { input ->
            decodeStrict(input, setOf("executionId", "patch")) {
                FormApplyPatchInput(input.requiredString("executionId"), input.requiredPatch())
            }
        }

        /** 创建绑定页面模型的注册项。 */
        fun registered(screen: DemoScreenModel): RegisteredAction<FormApplyPatchInput, JsonObject> = RegisteredAction(
            FormApplyPatchAction(screen),
            INPUT_CODEC,
            DEMO_JSON_OUTPUT_CODEC,
        )
    }
}
