package com.wzx.huitai.desktop.decision

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionPreviewChange
import com.wzx.huitai.action.port.ActionApproval
import com.wzx.huitai.action.port.ActionConfirmation
import com.wzx.huitai.action.port.ApprovalDecision
import com.wzx.huitai.action.port.ConfirmationDecision
import com.wzx.huitai.action.port.RiskEvaluation
import com.wzx.huitai.security.audit.AuditRedactor
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把 ActionBus 的挂起式确认/审批请求协调成 Compose 可观察的一次性决策。
 *
 * 每个 execution 同一时刻至多有一个 waiter；确认与高风险审批使用不同 phase，且所有完成路径都会
 * 先原子移除弹窗再完成 deferred，避免迟到点击、迟到 timeout 或其他 execution 的决定串线。
 */
class ComposeActionDecisionCoordinator(
    private val decisionTimeoutMillis: Long = DEFAULT_DECISION_TIMEOUT_MILLIS,
    private val recentTombstoneLimit: Int = DEFAULT_RECENT_TOMBSTONE_LIMIT,
    private val clock: () -> Instant = Instant::now,
    private val actionTitleResolver: (String) -> String = { it },
    private val redactor: AuditRedactor = AuditRedactor(),
    private val sensitiveValues: () -> Set<String> = { emptySet() },
    private val decisionIdFactory: (ActionDecisionPhase, String) -> String = { phase, executionId ->
        "${phase.name.lowercase()}-$executionId-${UUID.randomUUID()}"
    },
) {
    private val monitor = Any()
    private val pendingByExecution = linkedMapOf<String, PendingDecision>()
    private val recentTombstones = linkedMapOf<String, DecisionTombstone>()
    private val mutableState = MutableStateFlow(ActionDecisionState())
    private var stopped = false
    private var agentConnected = true

    /** Compose 只获得只读 StateFlow，不能绕过协调器直接修改决策。 */
    val state: StateFlow<ActionDecisionState> = mutableState.asStateFlow()

    /** 只暴露当前保留数量用于有界性验证，不泄漏 tombstone 内容或可变集合。 */
    internal val retainedTombstoneCount: Int
        get() = synchronized(monitor) { recentTombstones.size }

    init {
        require(decisionTimeoutMillis > 0) { "动作决策超时必须为正数" }
        require(recentTombstoneLimit > 0) { "近期决策墓碑上限必须为正数" }
    }

    /** 注册并等待当前 execution 的单次预览确认。 */
    suspend fun requestConfirmation(
        command: ActionCommand,
        preview: ActionPreview,
        context: ActionContext,
    ): ActionConfirmation {
        requireRequestScope(command, preview, context)
        val decisionId = decisionIdFactory(ActionDecisionPhase.CONFIRMATION, command.executionId)
        val deferred = CompletableDeferred<ActionConfirmation>()
        val pending = PendingConfirmation(
            dialog = confirmationDialog(command, preview, decisionId),
            deferred = deferred,
        )
        val blockedReason = synchronized(monitor) {
            when {
                stopped -> SHUTDOWN_REASON
                !agentConnected -> {
                    requireCanRegisterLocked(pending)
                    markConsumedLocked(pending, terminal = true)
                    DISCONNECTED_REASON
                }
                else -> {
                    registerLocked(pending)
                    null
                }
            }
        }
        if (blockedReason != null) {
            return confirmationResult(pending, ConfirmationDecision.REJECTED, blockedReason)
        }
        return awaitConfirmation(pending)
    }

    /** 注册并等待已确认高风险 execution 的独立单次审批。 */
    suspend fun requestApproval(
        command: ActionCommand,
        preview: ActionPreview,
        riskEvaluation: RiskEvaluation,
        context: ActionContext,
    ): ActionApproval {
        requireRequestScope(command, preview, context)
        val decisionId = decisionIdFactory(ActionDecisionPhase.HIGH_RISK_APPROVAL, command.executionId)
        val deferred = CompletableDeferred<ActionApproval>()
        val pending = PendingApproval(
            dialog = approvalDialog(command, preview, riskEvaluation, decisionId),
            actorId = context.identityScope.userId,
            deferred = deferred,
        )
        val blockedReason = synchronized(monitor) {
            when {
                stopped -> SHUTDOWN_REASON
                !agentConnected -> {
                    requireCanRegisterLocked(pending)
                    markConsumedLocked(pending, terminal = true)
                    DISCONNECTED_REASON
                }
                else -> {
                    registerLocked(pending)
                    null
                }
            }
        }
        if (blockedReason != null) return approvalResult(pending, ApprovalDecision.DENIED, blockedReason)
        return awaitApproval(pending)
    }

    /** 接受当前 execution 的预览；重复或迟到调用返回 false。 */
    fun accept(executionId: String): Boolean = resolveConfirmation(
        executionId,
        ConfirmationDecision.ACCEPTED,
        "用户已确认本次动作预览",
    )

    /** 拒绝当前 execution 的预览；该决定只取消本次动作。 */
    fun reject(executionId: String): Boolean = resolveConfirmation(
        executionId,
        ConfirmationDecision.REJECTED,
        "用户已取消本次动作预览",
    )

    /** 批准当前 execution 的高风险动作；不存在会话级或永久放行入口。 */
    fun approve(executionId: String): Boolean = resolveApproval(
        executionId,
        ApprovalDecision.APPROVED,
        "用户已明确批准本次高风险动作",
    )

    /** 拒绝当前 execution 的高风险动作。 */
    fun deny(executionId: String): Boolean = resolveApproval(
        executionId,
        ApprovalDecision.DENIED,
        "用户已拒绝本次高风险动作",
    )

    /** Agent 连接在执行前断开时，取消所有仍等待 UI 的 execution。 */
    fun onAgentDisconnected() {
        transitionAndResolveEveryPending(DISCONNECTED_REASON) { agentConnected = false }
    }

    /** 新连接完成认证后重新开放决策注册；shutdown 后的协调器不能恢复。 */
    fun onAgentConnected() {
        synchronized(monitor) {
            if (!stopped) agentConnected = true
        }
    }

    /** 桌面关闭时完成全部 waiter，并永久拒绝本协调器上的后续注册。 */
    fun shutdown() {
        transitionAndResolveEveryPending(SHUTDOWN_REASON) {
            stopped = true
            agentConnected = false
        }
    }

    /** timeout 与点击竞争时由 execution、phase 和对象身份共同保证 first-decision-wins。 */
    private suspend fun awaitConfirmation(pending: PendingConfirmation): ActionConfirmation = try {
        withTimeoutOrNull(decisionTimeoutMillis) { pending.deferred.await() } ?: run {
            expireConfirmation(pending)
            pending.deferred.await()
        }
    } catch (cancellation: CancellationException) {
        abandon(pending, "确认等待协程已取消")
        throw cancellation
    }

    /** 审批 timeout 只生成 EXPIRED，不会被映射成批准或执行。 */
    private suspend fun awaitApproval(pending: PendingApproval): ActionApproval = try {
        withTimeoutOrNull(decisionTimeoutMillis) { pending.deferred.await() } ?: run {
            expireApproval(pending)
            pending.deferred.await()
        }
    } catch (cancellation: CancellationException) {
        abandon(pending, "审批等待协程已取消")
        throw cancellation
    }

    /** 同一 execution 同一 phase 只能注册一次，且不同 phase 不能并发占用同一弹窗槽位。 */
    private fun registerLocked(pending: PendingDecision) {
        requireCanRegisterLocked(pending)
        val executionId = pending.dialog.executionId
        pendingByExecution[executionId] = pending
        publishLocked()
    }

    /** 统一检查 execution 与 phase 的一次性注册约束，断线立即取消也必须留下同样的消费事实。 */
    private fun requireCanRegisterLocked(pending: PendingDecision) {
        val executionId = pending.dialog.executionId
        val tombstone = recentTombstones[executionId]
        check(tombstone?.terminal != true) { "当前 execution 已完成决策" }
        check(pendingByExecution[executionId] == null) { "当前 execution 已有待处理决策" }
        check(pending.phase !in tombstone?.consumedPhases.orEmpty()) { "当前 execution 的该决策阶段已消费" }
    }

    /** 原子消费普通确认，phase 不匹配时不触碰任何 waiter。 */
    private fun resolveConfirmation(
        executionId: String,
        decision: ConfirmationDecision,
        reason: String,
    ): Boolean {
        val resolution = synchronized(monitor) {
            val pending = pendingByExecution[executionId] as? PendingConfirmation ?: return false
            val decidedAt = clock()
            val expired = decidedAt.toEpochMilli() >= pending.dialog.expiresAtEpochMillis
            val effectiveDecision = if (expired) ConfirmationDecision.EXPIRED else decision
            val effectiveReason = if (expired) CONFIRMATION_EXPIRED_REASON else reason
            removeLocked(pending, terminal = effectiveDecision != ConfirmationDecision.ACCEPTED)
            Triple(
                pending,
                confirmationResult(pending, effectiveDecision, effectiveReason, decidedAt),
                !expired,
            )
        }
        resolution.first.deferred.complete(resolution.second)
        return resolution.third
    }

    /** 原子消费高风险审批；任何审批决定都是该 execution 的最终 UI 决策。 */
    private fun resolveApproval(
        executionId: String,
        decision: ApprovalDecision,
        reason: String,
    ): Boolean {
        val resolution = synchronized(monitor) {
            val pending = pendingByExecution[executionId] as? PendingApproval ?: return false
            val decidedAt = clock()
            val expired = decidedAt.toEpochMilli() >= pending.dialog.expiresAtEpochMillis
            val effectiveDecision = if (expired) ApprovalDecision.EXPIRED else decision
            val effectiveReason = if (expired) APPROVAL_EXPIRED_REASON else reason
            removeLocked(pending, terminal = true)
            Triple(
                pending,
                approvalResult(pending, effectiveDecision, effectiveReason, decidedAt),
                !expired,
            )
        }
        resolution.first.deferred.complete(resolution.second)
        return resolution.third
    }

    /** 只在原 pending 仍占有 execution 时过期，迟到 timeout 不会关闭后续 execution。 */
    private fun expireConfirmation(expected: PendingConfirmation) {
        val result = synchronized(monitor) {
            if (pendingByExecution[expected.dialog.executionId] !== expected) return
            removeLocked(expected, terminal = true)
            confirmationResult(expected, ConfirmationDecision.EXPIRED, CONFIRMATION_EXPIRED_REASON)
        }
        expected.deferred.complete(result)
    }

    /** 高风险审批过期精确返回 ApprovalDecision.EXPIRED。 */
    private fun expireApproval(expected: PendingApproval) {
        val result = synchronized(monitor) {
            if (pendingByExecution[expected.dialog.executionId] !== expected) return
            removeLocked(expected, terminal = true)
            approvalResult(expected, ApprovalDecision.EXPIRED, APPROVAL_EXPIRED_REASON)
        }
        expected.deferred.complete(result)
    }

    /** 外层 ActionBus 被取消时关闭对应弹窗，避免遗留无人消费的 deferred。 */
    private fun abandon(expected: PendingDecision, reason: String) {
        when (expected) {
            is PendingConfirmation -> {
                val result = synchronized(monitor) {
                    if (pendingByExecution[expected.dialog.executionId] !== expected) return
                    removeLocked(expected, terminal = true)
                    confirmationResult(expected, ConfirmationDecision.REJECTED, reason)
                }
                expected.deferred.complete(result)
            }
            is PendingApproval -> {
                val result = synchronized(monitor) {
                    if (pendingByExecution[expected.dialog.executionId] !== expected) return
                    removeLocked(expected, terminal = true)
                    approvalResult(expected, ApprovalDecision.DENIED, reason)
                }
                expected.deferred.complete(result)
            }
        }
    }

    /** 断线或关闭时一次性摘除所有弹窗，再逐个完成 waiter。 */
    private fun transitionAndResolveEveryPending(reason: String, transitionLocked: () -> Unit) {
        val snapshot = synchronized(monitor) {
            transitionLocked()
            val values = pendingByExecution.values.toList()
            values.forEach { markConsumedLocked(it, terminal = true) }
            pendingByExecution.clear()
            if (values.isNotEmpty()) publishLocked()
            values
        }
        snapshot.forEach { pending ->
            when (pending) {
                is PendingConfirmation -> pending.deferred.complete(
                    confirmationResult(pending, ConfirmationDecision.REJECTED, reason),
                )
                is PendingApproval -> pending.deferred.complete(
                    approvalResult(pending, ApprovalDecision.DENIED, reason),
                )
            }
        }
    }

    /** 从等待队列移除并留下 phase tombstone，阻止重复注册。 */
    private fun removeLocked(pending: PendingDecision, terminal: Boolean) {
        pendingByExecution.remove(pending.dialog.executionId)
        markConsumedLocked(pending, terminal)
        publishLocked()
    }

    /** 记录 phase 已消费；拒绝、过期、断线与审批决定同时终结整个 execution。 */
    private fun markConsumedLocked(pending: PendingDecision, terminal: Boolean) {
        val executionId = pending.dialog.executionId
        val previous = recentTombstones.remove(executionId) ?: DecisionTombstone()
        recentTombstones[executionId] = previous.copy(
            consumedPhases = previous.consumedPhases + pending.phase,
            terminal = previous.terminal || terminal,
        )
        while (recentTombstones.size > recentTombstoneLimit) {
            val oldest = recentTombstones.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    /** 每次只发布复制后的不可变 List，内部 pending/deferred 从不进入 Compose。 */
    private fun publishLocked() {
        mutableState.value = ActionDecisionState(pendingByExecution.values.map { it.dialog })
    }

    /** 校验 ActionBus 冻结的命令、预览和上下文仍属于同一个 execution/page revision。 */
    private fun requireRequestScope(command: ActionCommand, preview: ActionPreview, context: ActionContext) {
        require(command.executionId.isNotBlank()) { "executionId 不能为空" }
        require(preview.executionId == command.executionId) { "动作预览 executionId 不匹配" }
        require(context.identityScope == command.identityScope) { "动作身份范围不匹配" }
        require(context.pageId == command.pageId) { "动作页面不匹配" }
        require(context.contextRevision == command.contextRevision) { "动作页面版本不匹配" }
    }

    /** 只把安全展示字段写入确认状态，不暴露命令 input 或完整身份。 */
    private fun confirmationDialog(
        command: ActionCommand,
        preview: ActionPreview,
        decisionId: String,
    ): ConfirmationDecisionDialogState {
        val secrets = normalizedSensitiveValues()
        return ConfirmationDecisionDialogState(
            executionId = command.executionId,
            decisionId = decisionId,
            actionTitle = sanitize(actionTitleResolver(command.actionId), secrets),
            origin = command.origin,
            summary = sanitize(preview.summary, secrets),
            differences = preview.changes.map { safeDifference(it, secrets) },
            warnings = preview.warnings.map { sanitize(it, secrets) },
            expiresAtEpochMillis = clock().toEpochMilli() + decisionTimeoutMillis,
        )
    }

    /** 高风险状态只增加已脱敏原因、固定身份摘要与固定远端副作用警告。 */
    private fun approvalDialog(
        command: ActionCommand,
        preview: ActionPreview,
        riskEvaluation: RiskEvaluation,
        decisionId: String,
    ): HighRiskApprovalDialogState {
        val secrets = normalizedSensitiveValues()
        return HighRiskApprovalDialogState(
            executionId = command.executionId,
            decisionId = decisionId,
            actionTitle = sanitize(actionTitleResolver(command.actionId), secrets),
            origin = command.origin,
            summary = sanitize(preview.summary, secrets),
            differences = preview.changes.map { safeDifference(it, secrets) },
            warnings = preview.warnings.map { sanitize(it, secrets) },
            expiresAtEpochMillis = clock().toEpochMilli() + decisionTimeoutMillis,
            riskReasons = riskEvaluation.reasons.ifEmpty { listOf(DEFAULT_HIGH_RISK_REASON) }
                .map { sanitize(it, secrets) },
            identitySummary = SAFE_IDENTITY_SUMMARY,
            remoteSideEffectWarning = REMOTE_SIDE_EFFECT_WARNING,
        )
    }

    /** 用审计 redactor 先按字段脱敏，再替换注册的敏感值，最后格式化为 UI 文本。 */
    private fun safeDifference(
        change: ActionPreviewChange,
        secrets: Set<String>,
    ): ActionDecisionDifference {
        val fieldKey = change.path.substringAfterLast('.').substringAfterLast('/').ifBlank { "value" }
        fun render(value: JsonElement?): String {
            if (change.redacted) return AuditRedactor.REDACTED
            val safeValue = redactor.redact(JsonObject(mapOf(fieldKey to (value ?: JsonNull))))[fieldKey]
            return sanitize(safeValue.toDisplayText(), secrets)
        }
        return ActionDecisionDifference(
            path = sanitize(change.path, secrets),
            before = render(change.before),
            after = render(change.after),
            redacted = change.redacted || render(change.before) == AuditRedactor.REDACTED ||
                render(change.after) == AuditRedactor.REDACTED,
        )
    }

    /** 过滤空值并按长度降序替换，避免短敏感值先替换破坏长值匹配。 */
    private fun normalizedSensitiveValues(): Set<String> = sensitiveValues()
        .asSequence()
        .filter(String::isNotBlank)
        .sortedByDescending(String::length)
        .toCollection(linkedSetOf())

    /** 文本字段在进入 StateFlow 前完成值级脱敏。 */
    private fun sanitize(value: String, secrets: Set<String>): String = secrets.fold(value) { safe, secret ->
        safe.replace(secret, AuditRedactor.REDACTED)
    }

    /** 构造 ActionBus 可直接消费的普通确认结果。 */
    private fun confirmationResult(
        pending: PendingConfirmation,
        decision: ConfirmationDecision,
        reason: String,
        decidedAt: Instant = clock(),
    ): ActionConfirmation = ActionConfirmation(
        decisionId = pending.dialog.decisionId,
        executionId = pending.dialog.executionId,
        decision = decision,
        decidedAt = decidedAt,
        reason = reason,
    )

    /** 构造 ActionBus 可直接消费的高风险审批结果，批准时保留内部 actor 但不进入 UI 状态。 */
    private fun approvalResult(
        pending: PendingApproval,
        decision: ApprovalDecision,
        reason: String,
        decidedAt: Instant = clock(),
    ): ActionApproval = ActionApproval(
        approvalId = pending.dialog.decisionId,
        executionId = pending.dialog.executionId,
        decision = decision,
        decidedAt = decidedAt,
        decidedBy = if (decision == ApprovalDecision.APPROVED) pending.actorId else null,
        reason = reason,
    )

    private sealed interface PendingDecision {
        val dialog: ActionDecisionDialogState
        val phase: ActionDecisionPhase
            get() = dialog.phase
    }

    private data class PendingConfirmation(
        override val dialog: ConfirmationDecisionDialogState,
        val deferred: CompletableDeferred<ActionConfirmation>,
    ) : PendingDecision

    private data class PendingApproval(
        override val dialog: HighRiskApprovalDialogState,
        val actorId: String,
        val deferred: CompletableDeferred<ActionApproval>,
    ) : PendingDecision

    /** 只保留近期 execution 的 phase 与终态摘要；ActionBus 仍负责全生命周期 duplicate 权威。 */
    private data class DecisionTombstone(
        val consumedPhases: Set<ActionDecisionPhase> = emptySet(),
        val terminal: Boolean = false,
    )

    companion object {
        private const val DEFAULT_DECISION_TIMEOUT_MILLIS = 60_000L
        private const val DEFAULT_RECENT_TOMBSTONE_LIMIT = 4_096
        private const val SHUTDOWN_REASON = "桌面正在关闭，动作未执行"
        private const val DISCONNECTED_REASON = "Agent 连接已断开，动作未执行"
        private const val CONFIRMATION_EXPIRED_REASON = "动作预览确认已过期"
        private const val APPROVAL_EXPIRED_REASON = "高风险动作审批已过期"
        private const val DEFAULT_HIGH_RISK_REASON = "当前动作被判定为高风险"
        private const val SAFE_IDENTITY_SUMMARY = "租户与用户已安全绑定（身份标识已隐藏）"
        private const val REMOTE_SIDE_EFFECT_WARNING =
            "此动作可能在远端产生不可逆副作用，请核对后仅批准本次执行。"
    }
}

/** 把 JSON 值转换为紧凑展示文本；对象/数组已先经过审计 redactor。 */
private fun JsonElement?.toDisplayText(): String = when (this) {
    null, JsonNull -> "未设置"
    is JsonPrimitive -> if (isString) content else toString()
    else -> toString()
}
