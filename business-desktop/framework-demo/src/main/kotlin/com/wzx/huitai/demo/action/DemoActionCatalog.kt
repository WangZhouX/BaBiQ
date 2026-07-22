package com.wzx.huitai.demo.action

import com.wzx.huitai.action.ActionInputCodec
import com.wzx.huitai.action.ActionInputDecodeResult
import com.wzx.huitai.action.ActionOutputCodec
import com.wzx.huitai.action.ActionRegistry
import com.wzx.huitai.action.RegisteredAction
import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ActionTarget
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.demo.action.form.FormApplyPatchAction
import com.wzx.huitai.demo.action.form.FormPreviewPatchAction
import com.wzx.huitai.demo.action.form.FormReadStateAction
import com.wzx.huitai.demo.action.page.PageNavigateAction
import com.wzx.huitai.demo.action.page.PageReadContextAction
import com.wzx.huitai.demo.action.remote.DemoSaveDraftAction
import com.wzx.huitai.demo.action.remote.DemoSubmitAction
import com.wzx.huitai.demo.gateway.FakeHuitaiGateway
import com.wzx.huitai.demo.model.DemoScreenModel
import com.wzx.huitai.presentation.form.FormPatch
import com.wzx.huitai.presentation.form.FormPatchCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 装配通用演示页面的七个强类型应用动作。 */
class DemoActionCatalog(
    screen: DemoScreenModel,
    gateway: FakeHuitaiGateway,
) {
    /** 固定顺序、不可扩张的七动作目录。 */
    val actions: List<RegisteredAction<*, *>> = listOf(
        PageNavigateAction.registered(screen),
        PageReadContextAction.registered(screen),
        FormReadStateAction.registered(screen),
        FormPreviewPatchAction.registered(screen),
        FormApplyPatchAction.registered(screen),
        DemoSaveDraftAction.registered(screen, gateway),
        DemoSubmitAction.registered(screen, gateway),
    )

    /** 创建并冻结可直接交给 ApplicationActionBus 的注册表。 */
    fun createRegistry(): ActionRegistry = ActionRegistry().also { registry ->
        actions.forEach(registry::register)
        registry.freeze()
    }
}

/** 演示动作统一使用结构化 JSON 对象输出。 */
internal val DEMO_JSON_OUTPUT_CODEC = ActionOutputCodec<JsonObject> { it }

/** 构造固定版本的通用演示动作描述符。 */
internal fun demoDescriptor(
    id: String,
    title: String,
    description: String,
    risk: ActionRiskLevel,
    replay: ActionReplayPolicy,
    reconciliation: ReconciliationPolicy = ReconciliationPolicy.NONE,
    requiredPermissions: Set<String> = emptySet(),
    inputSchema: JsonObject,
): ActionDescriptor = ActionDescriptor(
    id = id,
    version = 1,
    title = title,
    description = description,
    inputSchema = inputSchema,
    riskLevel = risk,
    requiredPermissions = requiredPermissions,
    target = ActionTarget(pageType = "demo_form", operation = id.substringAfter('.')),
    replayPolicy = replay,
    reconciliationPolicy = reconciliation,
)

/** 严格动作输入 codec 的公共结构校验。 */
internal inline fun <I : Any> decodeStrict(
    input: JsonObject,
    expectedKeys: Set<String>,
    decode: () -> I,
): ActionInputDecodeResult<I> = try {
    require(input.keys == expectedKeys) { "动作输入字段不匹配" }
    ActionInputDecodeResult.Success(decode())
} catch (_: Exception) {
    ActionInputDecodeResult.Failure(
        ActionError(ActionErrorCode.VALIDATION_FAILED, "动作输入无效"),
    )
}

/** 读取必填非空字符串，拒绝数字、布尔值和 null。 */
internal fun JsonObject.requiredString(key: String): String {
    val primitive = get(key) as? JsonPrimitive ?: throw IllegalArgumentException("字段类型错误")
    require(primitive.isString) { "字段类型错误" }
    return primitive.content.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("字段不能为空")
}

/** 读取并通过 presentation-core 唯一入口解码表单补丁。 */
internal fun JsonObject.requiredPatch(): FormPatch {
    val patch = get("patch")?.jsonObject ?: throw IllegalArgumentException("补丁类型错误")
    return FormPatchCodec.decode(patch.toString())
}

/** 构造拒绝额外属性的对象 schema。 */
internal fun strictSchema(vararg properties: Pair<String, String>): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("properties", buildJsonObject {
        properties.forEach { (name, type) ->
            put(name, buildJsonObject { put("type", type) })
        }
    })
    put("required", buildJsonArray { properties.forEach { add(JsonPrimitive(it.first)) } })
}

/** 表单补丁动作使用的完整结构 schema；executionId 仍由后端在出站前权威注入。 */
internal fun formPatchInputSchema(): JsonObject = strictObjectSchema(
    properties = linkedMapOf(
        "executionId" to typeSchema("string"),
        "patch" to strictObjectSchema(
            properties = linkedMapOf(
                "pageId" to typeSchema("string"),
                "baseRevision" to buildJsonObject {
                    put("type", "integer")
                    put("minimum", 0)
                },
                "changes" to buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", 256)
                    put("items", fieldChangeSchema())
                },
            ),
        ),
    ),
)

private fun fieldChangeSchema(): JsonObject = strictObjectSchema(
    properties = linkedMapOf(
        "fieldId" to typeSchema("string"),
        "previousValue" to buildJsonObject { },
        "newValue" to buildJsonObject { },
        "reason" to typeSchema("string"),
        "confidence" to buildJsonObject {
            put("type", "number")
            put("minimum", 0)
            put("maximum", 1)
        },
        "sourceReferences" to buildJsonObject {
            put("type", "array")
            put("maxItems", 64)
            put("items", strictObjectSchema(
                properties = linkedMapOf(
                    "type" to typeSchema("string"),
                    "id" to typeSchema("string"),
                    "label" to typeSchema("string"),
                ),
                required = listOf("type", "id"),
            ))
        },
    ),
    required = listOf("fieldId", "previousValue", "newValue", "reason", "confidence"),
)

private fun typeSchema(type: String): JsonObject = buildJsonObject { put("type", type) }

private fun strictObjectSchema(
    properties: LinkedHashMap<String, JsonObject>,
    required: List<String> = properties.keys.toList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("properties", buildJsonObject {
        properties.forEach { (name, schema) -> put(name, schema) }
    })
    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
}

/** 只有 executionId 的动作输入 codec。 */
internal fun <I : Any> executionOnlyCodec(factory: (String) -> I): ActionInputCodec<I> = ActionInputCodec { input ->
    decodeStrict(input, setOf("executionId")) {
        factory(input.requiredString("executionId"))
    }
}
