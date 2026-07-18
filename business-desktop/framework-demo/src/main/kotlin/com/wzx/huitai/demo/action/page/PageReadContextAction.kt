package com.wzx.huitai.demo.action.page

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
import com.wzx.huitai.demo.model.DemoScreenModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 页面上下文读取动作的强类型输入。 */
data class PageReadContextInput(val executionId: String)

/** 返回 DemoScreenModel 生成的同版本页面上下文。 */
class PageReadContextAction private constructor(
    private val screen: DemoScreenModel,
) : ApplicationAction<PageReadContextInput, JsonObject> {
    override val descriptor = demoDescriptor(
        id = "page.read_context",
        title = "读取页面上下文",
        description = "读取通用演示页面的结构化事实",
        risk = ActionRiskLevel.READ_ONLY,
        replay = ActionReplayPolicy.SAFE,
        inputSchema = strictSchema("executionId" to "string"),
    )

    /** 读取动作的预览不执行页面写入。 */
    override suspend fun preview(input: PageReadContextInput, context: ActionContext): ActionPreview =
        ActionPreview(input.executionId, "读取当前页面上下文")

    /** 将当前页面快照编码为结构化 JSON。 */
    override suspend fun execute(input: PageReadContextInput, context: ActionContext): ActionResult<JsonObject> =
        ActionResult.Success(
            executionId = input.executionId,
            output = Json.parseToJsonElement(Json.encodeToString(screen.pageContext())).jsonObject,
        )

    companion object {
        /** 创建绑定页面模型的注册项。 */
        fun registered(screen: DemoScreenModel): RegisteredAction<PageReadContextInput, JsonObject> = RegisteredAction(
            PageReadContextAction(screen),
            executionOnlyCodec(::PageReadContextInput),
            DEMO_JSON_OUTPUT_CODEC,
        )
    }
}
