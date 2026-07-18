package com.wzx.huitai.demo.action.page

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.ActionInputCodec
import com.wzx.huitai.action.ApplicationAction
import com.wzx.huitai.action.RegisteredAction
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionPreviewChange
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.demo.action.DEMO_JSON_OUTPUT_CODEC
import com.wzx.huitai.demo.action.decodeStrict
import com.wzx.huitai.demo.action.demoDescriptor
import com.wzx.huitai.demo.action.requiredString
import com.wzx.huitai.demo.action.strictSchema
import com.wzx.huitai.demo.model.DemoFormEvent
import com.wzx.huitai.demo.model.DemoScreenModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 页面导航动作的强类型输入。 */
data class PageNavigateInput(val executionId: String, val route: String)

/** 通过页面事件完成通用演示路由切换。 */
class PageNavigateAction private constructor(
    private val screen: DemoScreenModel,
) : ApplicationAction<PageNavigateInput, JsonObject> {
    override val descriptor = demoDescriptor(
        id = "page.navigate",
        title = "页面导航",
        description = "切换通用演示页面路由",
        risk = ActionRiskLevel.REVERSIBLE_WRITE,
        replay = ActionReplayPolicy.SAFE,
        requiredPermissions = setOf("demo.write"),
        inputSchema = strictSchema("executionId" to "string", "route" to "string"),
    )

    /** 只描述路由变化，不修改页面。 */
    override suspend fun preview(input: PageNavigateInput, context: ActionContext): ActionPreview = ActionPreview(
        executionId = input.executionId,
        summary = "导航到演示页面",
        changes = listOf(
            ActionPreviewChange(
                path = "route",
                before = JsonPrimitive(screen.state.value.route),
                after = JsonPrimitive(input.route),
            ),
        ),
    )

    /** 只派发强类型导航事件。 */
    override suspend fun execute(input: PageNavigateInput, context: ActionContext): ActionResult<JsonObject> {
        screen.dispatch(DemoFormEvent.Navigate(input.route))
        val state = screen.state.value
        return ActionResult.Success(
            executionId = input.executionId,
            output = buildJsonObject {
                put("route", state.route)
                put("revision", state.revision)
            },
        )
    }

    companion object {
        private val INPUT_CODEC = ActionInputCodec<PageNavigateInput> { input ->
            decodeStrict(input, setOf("executionId", "route")) {
                val route = input.requiredString("route")
                require(route.startsWith('/')) { "路由必须使用绝对格式" }
                PageNavigateInput(
                    executionId = input.requiredString("executionId"),
                    route = route,
                )
            }
        }

        /** 创建绑定页面模型的注册项。 */
        fun registered(screen: DemoScreenModel): RegisteredAction<PageNavigateInput, JsonObject> = RegisteredAction(
            PageNavigateAction(screen),
            INPUT_CODEC,
            DEMO_JSON_OUTPUT_CODEC,
        )
    }
}
