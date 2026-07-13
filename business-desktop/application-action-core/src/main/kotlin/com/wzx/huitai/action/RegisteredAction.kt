package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionResult
import kotlinx.serialization.json.JsonObject

/** JSON 边界上的动作调用结果。 */
sealed interface ActionInvocationResult {
    data class Previewed(val preview: ActionPreview) : ActionInvocationResult
    data class Executed(val result: ActionResult<*>) : ActionInvocationResult
    data class Reconciled(val result: ReconciliationResult) : ActionInvocationResult
    data class Failure(val error: ActionError) : ActionInvocationResult
}

/**
 * 把动作实现和输入 codec 强绑定为一个不可拆分注册项。
 *
 * @param I 解码后的动作输入类型。
 * @param O 动作成功输出类型。
 * @param action 强类型动作实现。
 * @param codec 与动作输入类型配对的 JSON codec。
 */
class RegisteredAction<I : Any, O : Any>(
    val action: ApplicationAction<I, O>,
    val codec: ActionInputCodec<I>,
) {
    val descriptor: ActionDescriptor
        get() = action.descriptor

    /** 解码后生成动作预览。 */
    suspend fun invokePreview(input: JsonObject, context: ActionContext): ActionInvocationResult =
        invokeWithDecodedInput(input) { decoded ->
            ActionInvocationResult.Previewed(action.preview(decoded, context))
        }

    /** 解码后执行动作。 */
    suspend fun invokeExecute(input: JsonObject, context: ActionContext): ActionInvocationResult =
        invokeWithDecodedInput(input) { decoded ->
            ActionInvocationResult.Executed(action.execute(decoded, context))
        }

    /** 解码后执行结果对账，不会回退到 execute。 */
    suspend fun invokeReconcile(
        input: JsonObject,
        context: ActionContext,
        remoteReference: String?,
    ): ActionInvocationResult = invokeWithDecodedInput(input) { decoded ->
        ActionInvocationResult.Reconciled(action.reconcile(decoded, context, remoteReference))
    }

    private fun decode(input: JsonObject): ActionInputDecodeResult<I> = try {
        codec.decode(input)
    } catch (_: Exception) {
        ActionInputDecodeResult.Failure(
            ActionError(ActionErrorCode.VALIDATION_FAILED, "动作输入解析失败"),
        )
    }

    /**
     * codec 与 action 在同一注册项内按 I 构造，擦除后仅在此私有桥恢复类型。
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun invokeWithDecodedInput(
        input: JsonObject,
        callback: suspend (I) -> ActionInvocationResult,
    ): ActionInvocationResult = when (val decoded = decode(input)) {
        is ActionInputDecodeResult.Failure -> ActionInvocationResult.Failure(decoded.error)
        is ActionInputDecodeResult.Success<*> -> callback(decoded.value as I)
    }

    /** 日志仅保留动作契约标识，不输出 codec、action 或输入数据。 */
    override fun toString(): String = "RegisteredAction(actionId=${descriptor.id}, version=${descriptor.version})"
}
