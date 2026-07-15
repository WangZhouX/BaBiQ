package com.wzx.huitai.security.audit

import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionAuditEvent
import com.wzx.huitai.action.port.ActionAuditPort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/**
 * 测试和框架演示使用的只追加内存审计端口。
 *
 * @param redactor 持久化边界的防御性脱敏器。
 */
class InMemoryActionAuditPort(
    private val redactor: AuditRedactor = AuditRedactor(),
) : ActionAuditPort {
    private val mutex = Mutex()
    private val eventsByExecution = mutableMapOf<String, MutableList<ActionAuditEvent>>()
    private val globalEvents = mutableListOf<ActionAuditEvent>()

    /** 追加调用方已分配序号的事件，并强制 execution 内从 1 连续递增。 */
    override suspend fun append(event: ActionAuditEvent) {
        mutex.withLock {
            appendLocked(event.copy(redactedPayload = redactor.redact(event.redactedPayload)))
        }
    }

    /**
     * 为审计草稿分配 execution 内下一个序号并原子追加。
     *
     * @param draft 与状态变更同边界产生的审计草稿。
     */
    suspend fun append(draft: ActionAuditDraft): ActionAuditEvent = mutex.withLock {
        val events = eventsByExecution[draft.executionId].orEmpty()
        val event = ActionAuditEvent(
            executionId = draft.executionId,
            sequence = (events.lastOrNull()?.sequence ?: 0L) + 1,
            fromState = draft.fromState,
            toState = draft.toState,
            type = draft.type,
            redactedPayload = redactor.redact(draft.redactedPayload),
            actorId = draft.actorId,
            occurredAt = draft.occurredAt,
        )
        appendLocked(event)
        event
    }

    /** 返回指定 execution 的不可修改有序快照。 */
    suspend fun events(executionId: String): List<ActionAuditEvent> = mutex.withLock {
        Collections.unmodifiableList(eventsByExecution[executionId].orEmpty().toList())
    }

    /** 返回所有 execution 按真实追加先后排列的不可修改快照。 */
    suspend fun events(): List<ActionAuditEvent> = mutex.withLock {
        Collections.unmodifiableList(globalEvents.toList())
    }

    /** 锁内校验序号并追加，任何失败都不会改变历史。 */
    private fun appendLocked(event: ActionAuditEvent) {
        require(event.executionId.isNotBlank()) { "审计 executionId 不能为空" }
        require(event.type.isNotBlank()) { "审计事件类型不能为空" }
        val events = eventsByExecution.getOrPut(event.executionId) { mutableListOf() }
        val expected = (events.lastOrNull()?.sequence ?: 0L) + 1
        require(event.sequence == expected) { "审计序号必须连续递增" }
        events += event
        globalEvents += event
    }
}
