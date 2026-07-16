package com.wzx.huitai.agent.client

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 序列或 epoch 违反当前桌面会话排序约束时抛出的稳定异常类型。 */
class ApplicationSequenceException(message: String) : IllegalArgumentException(message)

/**
 * 跟踪应用协议的会话级 envelope sequence 和连接级 republish 水位。
 *
 * Envelope sequence 在同一个 desktopSessionId 内始终严格递增；identity、catalog 和 context
 * 的首个正值则按 connectionId 分组，因此重连可重新发布与旧连接相等的业务版本。所有检查
 * 和推进在同一互斥锁内完成，失败不会污染水位。
 *
 * @param initialDesktopSessionId 当前 Agent 子进程的桌面会话标识。
 */
class ApplicationSequenceTracker(initialDesktopSessionId: String) {
    private val mutex = ReentrantLock()
    private var desktopSessionId: String = requireIdentifier("desktopSessionId", initialDesktopSessionId)
    private var lastEnvelopeSequence: Long = 0
    private val connectionCounters = mutableMapOf<String, ConnectionCounters>()

    /**
     * 切换桌面子进程会话；只有标识真正变化时才重置全部会话和连接计数器。
     */
    fun beginDesktopSession(newDesktopSessionId: String) = mutex.withLock {
        val validated = requireIdentifier("desktopSessionId", newDesktopSessionId)
        if (validated == desktopSessionId) return@withLock
        desktopSessionId = validated
        lastEnvelopeSequence = 0
        connectionCounters.clear()
    }

    /** 接受当前桌面会话内严格递增的 envelope sequence。 */
    fun acceptEnvelopeSequence(candidateDesktopSessionId: String, sequence: Long) = mutex.withLock {
        if (candidateDesktopSessionId != desktopSessionId) {
            throw ApplicationSequenceException("Envelope belongs to a different desktop session")
        }
        lastEnvelopeSequence = requireStrictIncrease("sequence", lastEnvelopeSequence, sequence)
    }

    /** 接受某连接首次正 identityEpoch，之后要求该连接内严格递增。 */
    fun acceptIdentityEpoch(connectionId: String, identityEpoch: Long) = mutex.withLock {
        val counters = countersFor(connectionId)
        counters.identityEpoch = requireStrictIncrease("identityEpoch", counters.identityEpoch, identityEpoch)
    }

    /** 接受某连接首次正 catalogEpoch，之后要求该连接内严格递增。 */
    fun acceptCatalogEpoch(connectionId: String, catalogEpoch: Long) = mutex.withLock {
        val counters = countersFor(connectionId)
        counters.catalogEpoch = requireStrictIncrease("catalogEpoch", counters.catalogEpoch, catalogEpoch)
    }

    /** 接受某连接首次正 contextSequence，之后要求该连接内严格递增。 */
    fun acceptContextSequence(connectionId: String, contextSequence: Long) = mutex.withLock {
        val counters = countersFor(connectionId)
        counters.contextSequence = requireStrictIncrease(
            "contextSequence",
            counters.contextSequence,
            contextSequence,
        )
    }

    /** connectionId 第一次出现时创建独立水位，旧连接的高值不会限制新连接首次重发。 */
    private fun countersFor(connectionId: String): ConnectionCounters {
        val validated = requireIdentifier("connectionId", connectionId)
        return connectionCounters.getOrPut(validated) { ConnectionCounters() }
    }

    /** 先校验再返回新水位，保证拒绝等值、过期值或非正值时原水位不推进。 */
    private fun requireStrictIncrease(name: String, current: Long, candidate: Long): Long {
        if (candidate <= 0) throw ApplicationSequenceException("$name must be positive")
        if (candidate <= current) throw ApplicationSequenceException("$name must strictly increase")
        return candidate
    }

    /** 标识只验证边界，不把具体值写入异常，避免稳定身份进入日志。 */
    private fun requireIdentifier(name: String, value: String): String {
        if (value.isBlank()) throw ApplicationSequenceException("$name must not be blank")
        return value
    }

    /** 单个连接内三类彼此独立的 republish 水位。 */
    private data class ConnectionCounters(
        var identityEpoch: Long = 0,
        var catalogEpoch: Long = 0,
        var contextSequence: Long = 0,
    )
}
