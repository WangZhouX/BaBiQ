package com.wzx.huitai.demo.gateway

import com.wzx.huitai.demo.model.DemoFormState

/** 假远端写入的确定性返回模式。 */
enum class FakeGatewayMode {
    CONFIRMED,
    RESPONSE_LOST_AFTER_WRITE,
}

/** 假远端写入结果。 */
sealed interface FakeGatewayResult {
    val remoteReference: String

    /** 远端明确确认写入成功。 */
    data class Confirmed(override val remoteReference: String) : FakeGatewayResult

    /** 远端已写入，但调用方没有收到确认响应。 */
    data class SentButResponseLost(override val remoteReference: String) : FakeGatewayResult
}

/** 按动作执行标识冻结保存的远端事实。 */
data class FakeRemoteRecord(
    /** 动作执行标识。 */
    val executionId: String,
    /** 首次写入时的不可变页面状态。 */
    val state: DemoFormState,
    /** 可供对账返回的远端引用。 */
    val remoteReference: String,
)

/**
 * 仅用于框架演示的内存网关。
 *
 * 同一 executionId 只记录第一次写入；响应丢失模式也必须先保存远端事实，再返回不确定结果。
 */
class FakeHuitaiGateway(
    private val draftMode: FakeGatewayMode = FakeGatewayMode.CONFIRMED,
    private val submitMode: FakeGatewayMode = FakeGatewayMode.CONFIRMED,
    private val beforeSubmit: () -> Unit = {},
) {
    private val drafts = linkedMapOf<String, FakeRemoteRecord>()
    private val submissions = linkedMapOf<String, FakeRemoteRecord>()

    /** 草稿保存请求次数，包含被幂等命中的重复调用。 */
    var draftRequestCount: Int = 0
        private set

    /** 提交请求次数，包含被幂等命中的重复调用。 */
    var submissionRequestCount: Int = 0
        private set

    /** 草稿真实写入次数。 */
    var draftWriteCount: Int = 0
        private set

    /** 提交真实写入次数。 */
    var submissionWriteCount: Int = 0
        private set

    /** 草稿远端查询次数。 */
    var draftQueryCount: Int = 0
        private set

    /** 提交远端查询次数。 */
    var submissionQueryCount: Int = 0
        private set

    /** 按 executionId 幂等保存草稿。 */
    @Synchronized
    fun saveDraft(executionId: String, state: DemoFormState): FakeGatewayResult {
        draftRequestCount += 1
        return write(executionId, state, "draft", drafts, draftMode) { draftWriteCount += 1 }
    }

    /** 按 executionId 幂等提交。 */
    @Synchronized
    fun submit(executionId: String, state: DemoFormState): FakeGatewayResult {
        submissionRequestCount += 1
        beforeSubmit()
        return write(executionId, state, "submission", submissions, submitMode) { submissionWriteCount += 1 }
    }

    /** 查询草稿远端事实。 */
    @Synchronized
    fun queryDraft(executionId: String): FakeRemoteRecord? {
        draftQueryCount += 1
        return drafts[executionId]
    }

    /** 查询提交远端事实。 */
    @Synchronized
    fun querySubmission(executionId: String): FakeRemoteRecord? {
        submissionQueryCount += 1
        return submissions[executionId]
    }

    private fun write(
        executionId: String,
        state: DemoFormState,
        kind: String,
        records: MutableMap<String, FakeRemoteRecord>,
        mode: FakeGatewayMode,
        countWrite: () -> Unit,
    ): FakeGatewayResult {
        records[executionId]?.let { return FakeGatewayResult.Confirmed(it.remoteReference) }
        val record = FakeRemoteRecord(
            executionId = executionId,
            state = state.copy(),
            remoteReference = "$kind-$executionId",
        )
        records[executionId] = record
        countWrite()
        return when (mode) {
            FakeGatewayMode.CONFIRMED -> FakeGatewayResult.Confirmed(record.remoteReference)
            FakeGatewayMode.RESPONSE_LOST_AFTER_WRITE ->
                FakeGatewayResult.SentButResponseLost(record.remoteReference)
        }
    }
}
