package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException

/** JSON 边界上的动作调用结果。 */
sealed interface ActionInvocationResult {
    data class Previewed(val preview: ActionPreview) : ActionInvocationResult
    data class Executed(val result: ActionResult<JsonElement>) : ActionInvocationResult
    data class Reconciled(val result: ReconciliationResult) : ActionInvocationResult
    data class Failure(val error: ActionError) : ActionInvocationResult

    /**
     * 业务动作已成功，但成功结果无法编码到 JSON 展示边界。
     *
     * Bus 必须保留 [terminalState] 对应的成功终态，单独记录协议/展示编码错误，绝不能重试 execute。
     */
    data class OutputEncodingFailed(
        val executionId: String,
        val terminalState: ActionExecutionState,
        val error: ActionError,
    ) : ActionInvocationResult {
        override fun toString(): String =
            "ActionInvocationResult.OutputEncodingFailed(executionId=$executionId, " +
                "terminalState=$terminalState, errorCode=${error.code})"
    }
}

/**
 * 把动作实现和输入 codec 强绑定为一个不可拆分注册项。
 *
 * @param I 解码后的动作输入类型。
 * @param O 动作成功输出类型。
 * @param action 强类型动作实现。
 * @param inputCodec 与动作输入类型配对的 JSON codec。
 * @param outputCodec 与动作输出类型配对的 JSON codec。
 */
class RegisteredAction<I : Any, O : Any>(
    val action: ApplicationAction<I, O>,
    val inputCodec: ActionInputCodec<I>,
    val outputCodec: ActionOutputCodec<O>,
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
            encodeExecutionResult(action.execute(decoded, context))
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
        inputCodec.decode(input)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ActionInputDecodeResult.Failure(
            ActionError(ActionErrorCode.VALIDATION_FAILED, "动作输入解析失败"),
        )
    }

    private fun encodeExecutionResult(result: ActionResult<O>): ActionInvocationResult = when (result) {
        is ActionResult.Success -> try {
            ActionInvocationResult.Executed(
                ActionResult.Success(
                    executionId = result.executionId,
                    output = outputCodec.encode(result.output),
                    redactedOutput = result.redactedOutput?.let(outputCodec::encode),
                    remoteReference = result.remoteReference,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ActionInvocationResult.OutputEncodingFailed(
                executionId = result.executionId,
                terminalState = ActionExecutionState.SUCCEEDED,
                error = ActionError(ActionErrorCode.PROTOCOL_ERROR, "动作输出编码失败"),
            )
        }
        else -> ActionInvocationResult.Executed(
            when (result) {
                is ActionResult.Preview -> ActionResult.Preview(result.preview)
                is ActionResult.ApprovalRequired -> ActionResult.ApprovalRequired(
                    executionId = result.executionId,
                    approvalId = result.approvalId,
                    preview = result.preview,
                    reason = result.reason,
                    expiresAtEpochMillis = result.expiresAtEpochMillis,
                )
                is ActionResult.Failure -> ActionResult.Failure(
                    executionId = result.executionId,
                    error = result.error,
                    remoteReference = result.remoteReference,
                )
                is ActionResult.Canceled -> ActionResult.Canceled(result.executionId, result.reason)
                is ActionResult.Expired -> ActionResult.Expired(result.executionId, result.reason)
                is ActionResult.OutcomeUnknown -> ActionResult.OutcomeUnknown(
                    executionId = result.executionId,
                    error = result.error,
                    remoteReference = result.remoteReference,
                    reconciliationPolicy = result.reconciliationPolicy,
                )
                is ActionResult.Success -> error("成功结果已在前置分支处理")
            },
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
