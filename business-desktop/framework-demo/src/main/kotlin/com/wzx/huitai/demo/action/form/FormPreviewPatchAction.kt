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
import com.wzx.huitai.demo.model.DemoScreenModel
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.demo.model.DemoFormEvent
import com.wzx.huitai.presentation.form.FormPatch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 表单补丁预览动作的强类型输入。 */
data class FormPreviewPatchInput(val executionId: String, val patch: FormPatch)

/** 校验补丁并安装为瞬态 UI 建议；不修改任何已提交字段值。 */
class FormPreviewPatchAction private constructor(
    private val screen: DemoScreenModel,
) : ApplicationAction<FormPreviewPatchInput, JsonObject> {
    override val descriptor = demoDescriptor(
        id = "form.preview_patch",
        title = "预览表单补丁",
        description = "预览绑定当前 revision 的字段变化",
        risk = ActionRiskLevel.READ_ONLY,
        replay = ActionReplayPolicy.SAFE,
        inputSchema = formPatchInputSchema(),
    )

    /** 根据补丁值生成无副作用变化列表。 */
    override suspend fun preview(input: FormPreviewPatchInput, context: ActionContext): ActionPreview = ActionPreview(
        executionId = input.executionId,
        summary = "预览 ${input.patch.changes.size} 项字段变化",
        changes = input.patch.changes.map { change ->
            ActionPreviewChange(change.fieldId, change.previousValue, change.newValue)
        },
        warnings = buildList {
            if (input.patch.pageId != DemoFormState.PAGE_ID) add("补丁页面不匹配")
            if (input.patch.baseRevision != screen.state.value.revision) add("补丁版本已过期")
        },
    )

    /** 原子安装与当前 page/revision 绑定的建议补丁，并返回不含字段正文的摘要。 */
    override suspend fun execute(input: FormPreviewPatchInput, context: ActionContext): ActionResult<JsonObject> {
        if (input.patch.pageId != DemoFormState.PAGE_ID) {
            return ActionResult.Failure(
                input.executionId,
                ActionError(ActionErrorCode.VALIDATION_FAILED, "补丁页面不匹配"),
            )
        }
        val transition = screen.dispatchWithExpectedContext(
            event = DemoFormEvent.SuggestPatch(input.patch),
            expectedPageId = context.pageId,
            expectedRevision = context.contextRevision,
        )
        if (transition == null || transition.after.suggestionPatch != input.patch) {
            return ActionResult.Failure(
                input.executionId,
                ActionError(ActionErrorCode.CONTEXT_STALE, "form suggestion context changed"),
            )
        }
        return ActionResult.Success(
            input.executionId,
            buildJsonObject {
                put("baseRevision", input.patch.baseRevision)
                put("changeCount", input.patch.changes.size)
                put("stale", input.patch.baseRevision != screen.state.value.revision)
            },
        )
    }

    companion object {
        private val INPUT_CODEC = ActionInputCodec<FormPreviewPatchInput> { input ->
            decodeStrict(input, setOf("executionId", "patch")) {
                FormPreviewPatchInput(input.requiredString("executionId"), input.requiredPatch())
            }
        }

        /** 创建绑定页面模型的注册项。 */
        fun registered(screen: DemoScreenModel): RegisteredAction<FormPreviewPatchInput, JsonObject> = RegisteredAction(
            FormPreviewPatchAction(screen),
            INPUT_CODEC,
            DEMO_JSON_OUTPUT_CODEC,
        )
    }
}
