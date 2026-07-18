package com.wzx.huitai.demo.action.form

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.ApplicationAction
import com.wzx.huitai.action.RegisteredAction
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.demo.action.DEMO_JSON_OUTPUT_CODEC
import com.wzx.huitai.demo.action.demoDescriptor
import com.wzx.huitai.demo.action.executionOnlyCodec
import com.wzx.huitai.demo.action.strictSchema
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.demo.model.DemoScreenModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 表单状态读取动作的强类型输入。 */
data class FormReadStateInput(val executionId: String)

/** 返回七字段表单当前值与 revision。 */
class FormReadStateAction private constructor(
    private val screen: DemoScreenModel,
) : ApplicationAction<FormReadStateInput, JsonObject> {
    override val descriptor = demoDescriptor(
        id = "form.read_state",
        title = "读取表单状态",
        description = "读取通用七字段表单状态",
        risk = ActionRiskLevel.READ_ONLY,
        replay = ActionReplayPolicy.SAFE,
        inputSchema = strictSchema("executionId" to "string"),
    )

    /** 读取预览没有任何副作用。 */
    override suspend fun preview(input: FormReadStateInput, context: ActionContext): ActionPreview =
        ActionPreview(input.executionId, "读取当前表单状态")

    /** 返回当前不可变状态的结构化快照。 */
    override suspend fun execute(input: FormReadStateInput, context: ActionContext): ActionResult<JsonObject> =
        ActionResult.Success(input.executionId, screen.state.value.toJson())

    companion object {
        /** 创建绑定页面模型的注册项。 */
        fun registered(screen: DemoScreenModel): RegisteredAction<FormReadStateInput, JsonObject> = RegisteredAction(
            FormReadStateAction(screen),
            executionOnlyCodec(::FormReadStateInput),
            DEMO_JSON_OUTPUT_CODEC,
        )
    }
}

/** 将七字段页面状态编码为无额外字段的 JSON。 */
internal fun DemoFormState.toJson(): JsonObject = buildJsonObject {
    put("pageId", DemoFormState.PAGE_ID)
    put("revision", revision)
    put("route", route)
    put("materialName", values.name)
    put("materialType", values.type)
    put("contact", values.contact)
    put("amount", values.amount)
    put("date", values.date)
    put("status", values.status)
    put("details", values.details)
}
