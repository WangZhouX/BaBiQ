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
import com.wzx.huitai.demo.action.formPatchInputSchema
import com.wzx.huitai.demo.action.requiredPatch
import com.wzx.huitai.demo.action.requiredString
import com.wzx.huitai.demo.model.DemoDispatchResult
import com.wzx.huitai.demo.model.DemoFormEvent
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.demo.model.DemoScreenModel
import com.wzx.huitai.presentation.form.FormPatch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        inputSchema = formPatchInputSchema(),
    )

    /** 根据补丁生成确认预览，不修改页面。 */
    override suspend fun preview(input: FormApplyPatchInput, context: ActionContext): ActionPreview = ActionPreview(
        executionId = input.executionId,
        summary = "应用 ${input.patch.changes.size} 项字段变化",
        changes = input.patch.changes.map { change ->
            ActionPreviewChange(change.fieldId, change.previousValue, change.newValue)
        },
    )

    /** 只派发强类型补丁事件，并依据该事件的原子迁移结果确认是否应用。 */
    override suspend fun execute(input: FormApplyPatchInput, context: ActionContext): ActionResult<JsonObject> {
        val transition = screen.dispatchWithExpectedContext(
            event = DemoFormEvent.ApplyPatch(input.patch),
            expectedPageId = context.pageId,
            expectedRevision = context.contextRevision,
        )
        if (transition == null) {
            return ActionResult.Failure(
                input.executionId,
                ActionError(ActionErrorCode.CONTEXT_STALE, "表单补丁上下文已变化"),
            )
        }
        if (!transition.applied(input.patch)) {
            return ActionResult.Failure(
                input.executionId,
                ActionError(ActionErrorCode.CONTEXT_STALE, "表单补丁未能应用"),
            )
        }
        return ActionResult.Success(
            input.executionId,
            buildJsonObject {
                put("revision", transition.after.revision)
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

/** 只接受本事件从 patch 基础版本出发、一次递增且实际落下全部字段值的迁移。 */
private fun DemoDispatchResult.applied(patch: FormPatch): Boolean =
    stateChanged &&
        before.revision == patch.baseRevision &&
        after.revision == patch.baseRevision + 1 &&
        patch.changes.all { change ->
            change.fieldId in DemoFormState.FIELD_IDS &&
                change.newValue == JsonPrimitive(after.values.valueOf(change.fieldId))
        }
