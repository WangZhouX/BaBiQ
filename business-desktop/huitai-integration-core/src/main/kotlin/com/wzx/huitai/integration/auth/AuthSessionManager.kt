package com.wzx.huitai.integration.auth

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 协调认证状态、无凭据身份快照与持久凭据。 */
class AuthSessionManager(
    private val credentialPersistence: AuthCredentialPersistencePort,
    private val stateMachine: AuthenticationStateMachine = AuthenticationStateMachine(),
    private val authSessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    internal val identityTransitionBufferCapacity: Int = 16,
) : AuthTokenProvider {
    init {
        require(identityTransitionBufferCapacity > 0) { "identityTransitionBufferCapacity must be positive" }
    }

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AuthenticationState.SIGNED_OUT)
    private val mutableIdentity = MutableStateFlow<AuthIdentitySnapshot?>(null)
    // UI 事件采用有界合并缓冲；权威状态由 StateFlow 提供，持久历史由 durable audit 承担。
    private val mutableIdentityTransitions =
        MutableSharedFlow<AuthIdentityTransition>(
            extraBufferCapacity = identityTransitionBufferCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    @Volatile
    private var credentialSnapshot = CredentialSnapshot(tokens = null, readable = false)
    private var identityEpoch: Long = 0

    val state: StateFlow<AuthenticationState> = mutableState.asStateFlow()
    val identity: StateFlow<AuthIdentitySnapshot?> = mutableIdentity.asStateFlow()
    val identityTransitions: SharedFlow<AuthIdentityTransition> = mutableIdentityTransitions.asSharedFlow()

    /** 建立首个身份；凭据成功替换后才发布已认证状态。 */
    suspend fun login(
        userId: String,
        tenantId: String,
        platformId: String,
        roles: Set<String>,
        permissions: Set<String>,
        authenticatedAt: Instant,
        tokens: AuthTokenSet,
    ) = mutex.withLock {
        val previousState = mutableState.value
        val previousIdentity = mutableIdentity.value
        val signingIn = stateMachine.transition(previousState, AuthenticationState.SIGNING_IN)
        val authenticated = stateMachine.transition(signingIn, AuthenticationState.AUTHENTICATED)
        val nextEpoch = identityEpoch + 1
        val identity = AuthIdentitySnapshot(
            authSessionId = authSessionIdFactory(),
            identityEpoch = nextEpoch,
            userId = userId,
            tenantId = tenantId,
            platformId = platformId,
            roles = frozenSet(roles),
            permissions = frozenSet(permissions),
            authenticatedAt = authenticatedAt,
        )
        val previousCredentialSnapshot = credentialSnapshot
        mutableState.value = signingIn
        credentialSnapshot = previousCredentialSnapshot.copy(readable = false)
        try {
            credentialPersistence.replace(tokens)
        } catch (failure: Throwable) {
            credentialSnapshot = previousCredentialSnapshot
            mutableState.value = previousState
            throw failure
        }
        identityEpoch = nextEpoch
        mutableIdentity.value = identity
        mutableState.value = authenticated
        credentialSnapshot = CredentialSnapshot(tokens = tokens, readable = true)
        publishTransition(
            AuthIdentityTransition(
                previousIdentity = previousIdentity,
                currentIdentity = identity,
                identityEpoch = identity.identityEpoch,
                fromState = signingIn,
                toState = AuthenticationState.AUTHENTICATED,
            ),
        )
    }

    /** 启动恢复只装载内部凭据，不推断登录状态或身份。 */
    suspend fun restoreCredentials() = mutex.withLock {
        val loaded = credentialPersistence.load()
        credentialSnapshot = CredentialSnapshot(tokens = loaded, readable = loaded != null)
    }

    /**
     * 刷新凭据和身份属性。
     *
     * 只有用户或租户变化才代表新的授权边界，需要提升 epoch 并创建新 session。
     */
    suspend fun refresh(
        userId: String,
        tenantId: String,
        platformId: String,
        roles: Set<String>,
        permissions: Set<String>,
        authenticatedAt: Instant,
        tokens: AuthTokenSet,
    ) = mutex.withLock {
        val previousState = mutableState.value
        val currentIdentity = checkNotNull(mutableIdentity.value) {
            "Authenticated identity is required for refresh"
        }
        val identityChanged = currentIdentity.userId != userId || currentIdentity.tenantId != tenantId
        val transitionState = if (identityChanged) {
            AuthenticationState.SWITCHING_TENANT
        } else {
            AuthenticationState.REFRESHING
        }
        val transitioning = stateMachine.transition(previousState, transitionState)
        val authenticated = stateMachine.transition(transitioning, AuthenticationState.AUTHENTICATED)
        val nextEpoch = if (identityChanged) identityEpoch + 1 else identityEpoch
        val refreshedIdentity = currentIdentity.copy(
            authSessionId = if (identityChanged) authSessionIdFactory() else currentIdentity.authSessionId,
            identityEpoch = if (identityChanged) nextEpoch else currentIdentity.identityEpoch,
            userId = userId,
            tenantId = tenantId,
            platformId = platformId,
            roles = frozenSet(roles),
            permissions = frozenSet(permissions),
            authenticatedAt = authenticatedAt,
        )

        // 持久化成功才发布新凭据与身份，避免内存和安全存储观察到不同版本。
        val previousCredentialSnapshot = credentialSnapshot
        mutableState.value = transitioning
        credentialSnapshot = previousCredentialSnapshot.copy(readable = false)
        try {
            credentialPersistence.replace(tokens)
        } catch (failure: Throwable) {
            credentialSnapshot = previousCredentialSnapshot
            mutableState.value = previousState
            throw failure
        }
        identityEpoch = nextEpoch
        mutableIdentity.value = refreshedIdentity
        mutableState.value = authenticated
        credentialSnapshot = CredentialSnapshot(tokens = tokens, readable = true)
        publishTransition(
            AuthIdentityTransition(
                previousIdentity = currentIdentity,
                currentIdentity = refreshedIdentity,
                identityEpoch = refreshedIdentity.identityEpoch,
                fromState = transitioning,
                toState = AuthenticationState.AUTHENTICATED,
            ),
        )
    }

    /** 登出时先通知身份边界失效，再清空身份和凭据。 */
    suspend fun logout() = mutex.withLock {
        val previousState = mutableState.value
        val previousIdentity = mutableIdentity.value
        val signedOut = stateMachine.transition(previousState, AuthenticationState.SIGNED_OUT)
        val nextEpoch = identityEpoch + 1
        val transition = AuthIdentityTransition(
            previousIdentity = previousIdentity,
            currentIdentity = null,
            identityEpoch = nextEpoch,
            fromState = previousState,
            toState = signedOut,
        )

        // 清理失败时保持完整旧会话，允许调用方安全重试。
        val previousCredentialSnapshot = credentialSnapshot
        try {
            credentialPersistence.clear()
        } catch (failure: Throwable) {
            credentialSnapshot = previousCredentialSnapshot
            throw failure
        }
        credentialSnapshot = CredentialSnapshot(tokens = null, readable = false)
        identityEpoch = nextEpoch
        mutableState.value = signedOut
        publishTransition(transition)
        mutableIdentity.value = null
    }

    /** 将当前认证收束为普通凭据失效，并删除本地身份和凭据。 */
    suspend fun expireAuthentication() = expire(AuthenticationState.EXPIRED)

    /** 将当前认证收束为会员失效，并保留独立终态供 UI 和策略判断。 */
    suspend fun expireMembership() = expire(AuthenticationState.MEMBERSHIP_EXPIRED)

    /** 在同一临界区发布失效事件并完成凭据清理。 */
    private suspend fun expire(targetState: AuthenticationState) = mutex.withLock {
        val previousState = mutableState.value
        val previousIdentity = mutableIdentity.value
        val transitionSource = if (previousState == AuthenticationState.AUTHENTICATED) {
            stateMachine.transition(previousState, AuthenticationState.REFRESHING)
        } else {
            previousState
        }
        val terminal = stateMachine.transition(transitionSource, targetState)
        val nextEpoch = identityEpoch + 1
        val transition = AuthIdentityTransition(
            previousIdentity = previousIdentity,
            currentIdentity = null,
            identityEpoch = nextEpoch,
            fromState = transitionSource,
            toState = terminal,
        )

        // 清理失败不发布终态，避免持久凭据与公开会话状态相互矛盾。
        val previousCredentialSnapshot = credentialSnapshot
        mutableState.value = transitionSource
        credentialSnapshot = previousCredentialSnapshot.copy(readable = false)
        try {
            credentialPersistence.clear()
        } catch (failure: Throwable) {
            credentialSnapshot = previousCredentialSnapshot
            mutableState.value = previousState
            throw failure
        }
        credentialSnapshot = CredentialSnapshot(tokens = null, readable = false)
        identityEpoch = nextEpoch
        mutableState.value = terminal
        publishTransition(transition)
        mutableIdentity.value = null
    }

    /** 仅供模块内传输层读取访问 token。 */
    override suspend fun accessToken(): String? = credentialSnapshot.readableTokens()?.accessToken

    /** 仅供模块内刷新协调器读取刷新 token。 */
    override suspend fun refreshToken(): String? = credentialSnapshot.readableTokens()?.refreshToken

    /** 非阻塞发布身份事件，避免慢订阅者在认证互斥区内造成全局停顿。 */
    private fun publishTransition(transition: AuthIdentityTransition) {
        mutableIdentityTransitions.tryEmit(transition)
    }

    /** 单一 volatile 快照保证凭据内容与可读状态不会被跨线程拆开观察。 */
    private data class CredentialSnapshot(
        val tokens: AuthTokenSet?,
        val readable: Boolean,
    ) {
        fun readableTokens(): AuthTokenSet? = tokens?.takeIf { readable }
    }
}
