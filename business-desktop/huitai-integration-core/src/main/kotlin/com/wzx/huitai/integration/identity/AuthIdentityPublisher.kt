package com.wzx.huitai.integration.identity

import com.wzx.huitai.integration.auth.AuthIdentitySnapshot
import com.wzx.huitai.integration.auth.AuthIdentityTransition
import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthenticationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 将认证会话的权威身份边界发布给 Agent 连接。
 *
 * `start` 先订阅无 replay transition，再读取 StateFlow 做补偿；二者最终经过同一 Mutex 去重和排序。
 */
class AuthIdentityPublisher(
    private val authSessionManager: AuthSessionManager,
    private val bindingPort: IdentityBindingPort,
    private val scope: CoroutineScope,
    private val onSignedOutPublished: suspend () -> Unit = {},
) : AutoCloseable {
    private val publicationMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private var collectionJob: Job? = null
    private var lastPublished: AuthIdentityBinding? = null

    /**
     * 启动 transition 订阅并补偿启动窗口内已经存在的权威认证身份。
     *
     * 重复调用不会创建第二个收集协程，也不会重复发送 bind。
     */
    suspend fun start() {
        lifecycleMutex.withLock {
            if (collectionJob?.isActive == true) return
            val startedJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                authSessionManager.identityTransitions.collect { transition ->
                    publish(transition)
                }
            }
            collectionJob = startedJob
            try {
                reconcileAuthoritativeIdentity()
            } catch (failure: Throwable) {
                startedJob.cancel()
                if (collectionJob === startedJob) collectionJob = null
                throw failure
            }
        }
    }

    /**
     * 发布单个认证边界迁移。
     *
     * 返回 true 表示发送了 bind/update，返回 false 只表示同一权威身份的重复补偿或普通刷新无需发送。
     */
    suspend fun publish(transition: AuthIdentityTransition): Boolean = publicationMutex.withLock {
        if (!isStructurallyValid(transition)) return@withLock false
        val next = AuthIdentityBinding(
            identityEpoch = transition.identityEpoch,
            identity = transition.currentIdentity,
        )
        val previous = lastPublished

        if (previous == null) {
            if (next.signedOut) return@withLock false
            bindingPort.bind(next)
            lastPublished = next
            return@withLock true
        }

        if (next.identityEpoch < previous.identityEpoch) {
            return@withLock false
        }
        if (next.identityEpoch == previous.identityEpoch) {
            if (sameBoundary(previous.identity, next.identity)) return@withLock false
            return@withLock false
        }

        bindingPort.update(next)
        lastPublished = next
        if (next.signedOut) onSignedOutPublished()
        true
    }

    /** 只取消本发布器创建的收集任务，不取消调用方注入的 CoroutineScope。 */
    override fun close() {
        collectionJob?.cancel()
        collectionJob = null
    }

    private suspend fun reconcileAuthoritativeIdentity() {
        if (authSessionManager.state.value != AuthenticationState.AUTHENTICATED) return
        val identity = authSessionManager.identity.value ?: return
        publicationMutex.withLock {
            val previous = lastPublished
            if (previous == null) {
                val binding = AuthIdentityBinding(identity.identityEpoch, identity)
                bindingPort.bind(binding)
                lastPublished = binding
                return@withLock
            }
            if (identity.identityEpoch < previous.identityEpoch) {
                return@withLock
            }
            if (identity.identityEpoch == previous.identityEpoch) {
                return@withLock
            }
            val binding = AuthIdentityBinding(identity.identityEpoch, identity)
            bindingPort.update(binding)
            lastPublished = binding
        }
    }

    private fun isStructurallyValid(transition: AuthIdentityTransition): Boolean {
        val current = transition.currentIdentity
        return current == null || current.identityEpoch == transition.identityEpoch
    }

    private fun sameBoundary(left: AuthIdentitySnapshot?, right: AuthIdentitySnapshot?): Boolean {
        if (left == null || right == null) return left == right
        return left.authSessionId == right.authSessionId &&
            left.identityEpoch == right.identityEpoch &&
            left.userId == right.userId &&
            left.tenantId == right.tenantId
    }
}
