package com.wzx.huitai.integration.auth

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthSessionManagerTest {
    @Test
    fun `登录建立首个身份并原子替换持久凭据`() = runTest {
        val persistence = RecordingCredentialPersistence()
        val manager = AuthSessionManager(persistence)
        val tokens = AuthTokenSet(
            accessToken = "access-secret",
            refreshToken = "refresh-secret",
        )

        manager.login(
            userId = "user-1",
            tenantId = "tenant-1",
            platformId = "platform-1",
            roles = setOf("lawyer"),
            permissions = setOf("case:read"),
            authenticatedAt = Instant.parse("2026-07-15T00:00:00Z"),
            tokens = tokens,
        )

        assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
        val identity = assertNotNull(manager.identity.value)
        assertEquals(1L, identity.identityEpoch)
        assertTrue(identity.authSessionId.isNotBlank())
        assertEquals(tokens, persistence.replaced)
    }

    @Test
    fun `token类型字符串输出不泄露任何原始凭据`() {
        val tokens = AuthTokenSet(
            accessToken = "access-secret",
            refreshToken = "refresh-secret",
        )

        val rendered = tokens.toString()

        assertFalse(rendered.contains("access-secret"))
        assertFalse(rendered.contains("refresh-secret"))
        assertTrue(rendered.contains("REDACTED"))
    }

    @Test
    fun `同身份刷新保持epoch和session并替换持久凭据`() = runTest {
        val persistence = RecordingCredentialPersistence()
        val manager = AuthSessionManager(persistence)
        manager.login(tokens = tokenSet("initial"), identity = identityArguments)
        val initialIdentity = assertNotNull(manager.identity.value)
        val refreshedTokens = tokenSet("refreshed")

        refreshWith(manager, tokens = refreshedTokens, identity = identityArguments)

        val refreshedIdentity = assertNotNull(manager.identity.value)
        assertEquals(initialIdentity.identityEpoch, refreshedIdentity.identityEpoch)
        assertEquals(initialIdentity.authSessionId, refreshedIdentity.authSessionId)
        assertEquals(refreshedTokens, persistence.replaced)
        assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
    }

    @Test
    fun `刷新发生用户或租户变化时提升epoch并创建新session`() = runTest {
        listOf(
            identityArguments.copy(userId = "user-2"),
            identityArguments.copy(tenantId = "tenant-2"),
        ).forEach { changedIdentity ->
            val persistence = RecordingCredentialPersistence()
            val manager = AuthSessionManager(persistence)
            manager.login(tokens = tokenSet("initial"), identity = identityArguments)
            val initialIdentity = assertNotNull(manager.identity.value)

            refreshWith(manager, tokens = tokenSet("changed"), identity = changedIdentity)

            val refreshedIdentity = assertNotNull(manager.identity.value)
            assertEquals(initialIdentity.identityEpoch + 1, refreshedIdentity.identityEpoch)
            assertNotEquals(initialIdentity.authSessionId, refreshedIdentity.authSessionId)
            assertEquals(changedIdentity.userId, refreshedIdentity.userId)
            assertEquals(changedIdentity.tenantId, refreshedIdentity.tenantId)
        }
    }

    @Test
    fun `登出先发布身份迁移再清身份和持久凭据`() = runTest {
        lateinit var manager: AuthSessionManager
        var identityWhenCleared: AuthIdentitySnapshot? = null
        val persistence = RecordingCredentialPersistence(
            onClear = { identityWhenCleared = manager.identity.value },
        )
        manager = AuthSessionManager(persistence)
        manager.login(tokens = tokenSet("initial"), identity = identityArguments)
        val oldIdentity = assertNotNull(manager.identity.value)
        var observedTransition: AuthIdentityTransition? = null
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.identityTransitions.take(1).collect { transition ->
                observedTransition = transition
            }
        }

        manager.logout()
        collector.join()

        val transition = assertNotNull(observedTransition)
        assertEquals(oldIdentity, transition.previousIdentity)
        assertNull(transition.currentIdentity)
        assertEquals(oldIdentity.identityEpoch + 1, transition.identityEpoch)
        assertEquals(AuthenticationState.AUTHENTICATED, transition.fromState)
        assertEquals(AuthenticationState.SIGNED_OUT, transition.toState)
        assertNull(manager.identity.value)
        assertEquals(oldIdentity, identityWhenCleared)
        assertEquals(1, persistence.clearCount)
        assertEquals(AuthenticationState.SIGNED_OUT, manager.state.value)
    }

    @Test
    fun `认证失效清理身份凭据并保持EXPIRED状态`() = runTest {
        val persistence = RecordingCredentialPersistence()
        val manager = AuthSessionManager(persistence)
        manager.login(tokens = tokenSet("initial"), identity = identityArguments)

        manager.expireAuthentication()

        assertEquals(AuthenticationState.EXPIRED, manager.state.value)
        assertNull(manager.identity.value)
        assertEquals(1, persistence.clearCount)
    }

    @Test
    fun `会员失效清理身份凭据并保持MEMBERSHIP_EXPIRED状态`() = runTest {
        val persistence = RecordingCredentialPersistence()
        val manager = AuthSessionManager(persistence)
        manager.login(tokens = tokenSet("initial"), identity = identityArguments)

        manager.expireMembership()

        assertEquals(AuthenticationState.MEMBERSHIP_EXPIRED, manager.state.value)
        assertNull(manager.identity.value)
        assertEquals(1, persistence.clearCount)
    }

    @Test
    fun `失效终态必须先登出才能重新登录`() = runTest {
        listOf<suspend (AuthSessionManager) -> Unit>(
            { it.expireAuthentication() },
            { it.expireMembership() },
        ).forEach { expire ->
            val manager = AuthSessionManager(RecordingCredentialPersistence())
            manager.login(tokens = tokenSet("initial"), identity = identityArguments)
            expire(manager)

            assertFailsWith<IllegalArgumentException> {
                manager.login(tokens = tokenSet("rejected"), identity = identityArguments)
            }

            manager.logout()
            manager.login(tokens = tokenSet("accepted"), identity = identityArguments)
            assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
        }
    }

    @Test
    fun `恢复只加载内部凭据且不发布认证身份或泄露token`() = runTest {
        val restoredTokens = tokenSet("restored-secret")
        val persistence = RecordingCredentialPersistence(loaded = restoredTokens)
        val manager = AuthSessionManager(persistence)
        val tokenProvider: AuthTokenProvider = manager

        manager.restoreCredentials()

        assertEquals(1, persistence.loadCount)
        assertEquals(AuthenticationState.SIGNED_OUT, manager.state.value)
        assertNull(manager.identity.value)
        assertTrue(manager.identityTransitions.replayCache.isEmpty())
        assertEquals(restoredTokens.accessToken, tokenProvider.accessToken())
        assertEquals(restoredTokens.refreshToken, tokenProvider.refreshToken())
        listOf(
            manager.toString(),
            manager.state.value.toString(),
            manager.identity.value.toString(),
            manager.identityTransitions.toString(),
            restoredTokens.toString(),
        ).forEach { rendered ->
            assertFalse(rendered.contains(restoredTokens.accessToken), message = rendered)
            assertFalse(rendered.contains(restoredTokens.refreshToken), message = rendered)
        }
    }

    @Test
    fun `恢复没有持久凭据时保持内部token为空`() = runTest {
        val persistence = RecordingCredentialPersistence(loaded = null)
        val manager = AuthSessionManager(persistence)
        val tokenProvider: AuthTokenProvider = manager

        manager.restoreCredentials()

        assertEquals(1, persistence.loadCount)
        assertNull(tokenProvider.accessToken())
        assertNull(tokenProvider.refreshToken())
        assertEquals(AuthenticationState.SIGNED_OUT, manager.state.value)
        assertNull(manager.identity.value)
    }

    @Test
    fun `登录持久替换失败时完整回滚且重试仍从epoch一开始`() = runTest {
        val persistence = RecordingCredentialPersistence()
        persistence.failReplace = true
        val manager = AuthSessionManager(persistence)
        val tokenProvider: AuthTokenProvider = manager

        assertFailsWith<IllegalStateException> {
            manager.login(tokens = tokenSet("failed"), identity = identityArguments)
        }

        assertEquals(AuthenticationState.SIGNED_OUT, manager.state.value)
        assertNull(manager.identity.value)
        assertNull(tokenProvider.accessToken())
        persistence.failReplace = false
        manager.login(tokens = tokenSet("retry"), identity = identityArguments)
        assertEquals(1L, assertNotNull(manager.identity.value).identityEpoch)
    }

    @Test
    fun `刷新持久替换失败时同身份和换租户均完整回滚`() = runTest {
        listOf(identityArguments, identityArguments.copy(tenantId = "tenant-2")).forEach { refreshedArguments ->
            val persistence = RecordingCredentialPersistence()
            val manager = AuthSessionManager(persistence)
            val tokenProvider: AuthTokenProvider = manager
            val oldTokens = tokenSet("old")
            manager.login(tokens = oldTokens, identity = identityArguments)
            val oldIdentity = assertNotNull(manager.identity.value)
            persistence.failReplace = true

            assertFailsWith<IllegalStateException> {
                refreshWith(manager, tokenSet("failed"), refreshedArguments)
            }

            assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
            assertEquals(oldIdentity, manager.identity.value)
            assertEquals(oldTokens.accessToken, tokenProvider.accessToken())
            persistence.failReplace = false
            refreshWith(manager, tokenSet("retry"), refreshedArguments)
            val retriedIdentity = assertNotNull(manager.identity.value)
            val expectedEpoch = if (refreshedArguments.tenantId == oldIdentity.tenantId) {
                oldIdentity.identityEpoch
            } else {
                oldIdentity.identityEpoch + 1
            }
            assertEquals(expectedEpoch, retriedIdentity.identityEpoch)
        }
    }

    @Test
    fun `登出清理失败时不发布任何可观察变更且可安全重试`() = runTest {
        val persistence = RecordingCredentialPersistence()
        val manager = AuthSessionManager(persistence)
        val tokenProvider: AuthTokenProvider = manager
        val oldTokens = tokenSet("old")
        manager.login(tokens = oldTokens, identity = identityArguments)
        val oldIdentity = assertNotNull(manager.identity.value)
        val transitions = mutableListOf<AuthIdentityTransition>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.identityTransitions.collect { transitions += it }
        }
        persistence.failClear = true

        assertFailsWith<IllegalStateException> { manager.logout() }

        assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
        assertEquals(oldIdentity, manager.identity.value)
        assertEquals(oldTokens.accessToken, tokenProvider.accessToken())
        assertTrue(transitions.isEmpty())
        persistence.failClear = false
        manager.logout()
        runCurrent()
        assertEquals(1, transitions.size)
        collector.cancel()
    }

    @Test
    fun `两类失效清理失败时保持调用前会话且可安全重试`() = runTest {
        val expireAuth: suspend (AuthSessionManager) -> Unit = { manager -> manager.expireAuthentication() }
        val expireMember: suspend (AuthSessionManager) -> Unit = { manager -> manager.expireMembership() }
        listOf<Pair<suspend (AuthSessionManager) -> Unit, AuthenticationState>>(
            expireAuth to AuthenticationState.EXPIRED,
            expireMember to AuthenticationState.MEMBERSHIP_EXPIRED,
        ).forEach { (expire, expectedTerminal) ->
            val persistence = RecordingCredentialPersistence()
            val manager = AuthSessionManager(persistence)
            val tokenProvider: AuthTokenProvider = manager
            val oldTokens = tokenSet(expectedTerminal.name)
            manager.login(tokens = oldTokens, identity = identityArguments)
            val oldIdentity = assertNotNull(manager.identity.value)
            val transitions = mutableListOf<AuthIdentityTransition>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                manager.identityTransitions.collect { transitions += it }
            }
            persistence.failClear = true

            assertFailsWith<IllegalStateException> { expire(manager) }

            assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
            assertEquals(oldIdentity, manager.identity.value)
            assertEquals(oldTokens.accessToken, tokenProvider.accessToken())
            assertTrue(transitions.isEmpty())
            persistence.failClear = false
            expire(manager)
            runCurrent()
            assertEquals(expectedTerminal, manager.state.value)
            assertEquals(1, transitions.size)
            collector.cancel()
        }
    }

    @Test
    fun `身份集合与迁移快照均是真不可变副本`() = runTest {
        val mutableRoles = mutableSetOf("lawyer", "reviewer")
        val mutablePermissions = mutableSetOf("case:read", "document:read")
        val manager = AuthSessionManager(RecordingCredentialPersistence())
        var transition: AuthIdentityTransition? = null
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.identityTransitions.take(1).collect { transition = it }
        }

        manager.login(
            tokens = tokenSet("immutable"),
            identity = identityArguments.copy(roles = mutableRoles, permissions = mutablePermissions),
        )
        collector.join()
        mutableRoles += "admin"
        mutablePermissions += "case:delete"

        val identity = assertNotNull(manager.identity.value)
        assertEquals(setOf("lawyer", "reviewer"), identity.roles)
        assertEquals(setOf("case:read", "document:read"), identity.permissions)
        assertFailsWith<UnsupportedOperationException> { (identity.roles as MutableSet<String>) += "attacker" }
        assertFailsWith<UnsupportedOperationException> { (identity.permissions as MutableSet<String>) += "attacker" }
        val transitionIdentity = assertNotNull(assertNotNull(transition).currentIdentity)
        assertEquals(setOf("lawyer", "reviewer"), transitionIdentity.roles)
        assertEquals(setOf("case:read", "document:read"), transitionIdentity.permissions)
    }

    @Test
    fun `直接构造身份快照也冻结集合边界`() {
        val mutableRoles = mutableSetOf("lawyer", "reviewer")
        val mutablePermissions = mutableSetOf("case:read", "document:read")
        val snapshot = AuthIdentitySnapshot(
            authSessionId = "session-direct",
            identityEpoch = 1,
            userId = "user-1",
            tenantId = "tenant-1",
            platformId = "platform-1",
            roles = mutableRoles,
            permissions = mutablePermissions,
            authenticatedAt = Instant.parse("2026-07-15T00:00:00Z"),
        )

        mutableRoles += "admin"
        mutablePermissions += "case:delete"

        assertEquals(setOf("lawyer", "reviewer"), snapshot.roles)
        assertEquals(setOf("case:read", "document:read"), snapshot.permissions)
        assertFailsWith<UnsupportedOperationException> { (snapshot.roles as MutableSet<String>) += "attacker" }
        assertFailsWith<UnsupportedOperationException> { (snapshot.permissions as MutableSet<String>) += "attacker" }
    }

    @Test
    fun `慢订阅者处理事件时认证操作仍能完成且事件准确交付`() = runTest {
        val manager = AuthSessionManager(RecordingCredentialPersistence())
        val firstReceived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<AuthIdentityTransition>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.identityTransitions.take(2).collect { transition ->
                received += transition
                if (received.size == 1) {
                    firstReceived.complete(Unit)
                    release.await()
                }
            }
        }
        manager.login(tokens = tokenSet("initial"), identity = identityArguments)
        firstReceived.await()

        val logout = async { manager.logout() }
        withTimeout(1_000) { logout.await() }

        assertEquals(AuthenticationState.SIGNED_OUT, manager.state.value)
        release.complete(Unit)
        collector.join()
        assertEquals(
            listOf(AuthenticationState.AUTHENTICATED, AuthenticationState.SIGNED_OUT),
            received.map { it.toState },
        )
    }

    @Test
    fun `慢订阅者阻塞时十一条身份事件仍全部非阻塞有序交付`() = runTest {
        val manager = AuthSessionManager(RecordingCredentialPersistence())
        val firstReceived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<AuthIdentityTransition>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.identityTransitions.take(11).collect { transition ->
                received += transition
                if (received.size == 1) {
                    firstReceived.complete(Unit)
                    release.await()
                }
            }
        }
        manager.login(tokens = tokenSet("initial"), identity = identityArguments)
        firstReceived.await()

        repeat(5) { index ->
            withTimeout(1_000) { manager.logout() }
            withTimeout(1_000) {
                manager.login(tokens = tokenSet("burst-$index"), identity = identityArguments)
            }
        }

        release.complete(Unit)
        collector.join()
        assertEquals(
            List(11) { index ->
                if (index % 2 == 0) AuthenticationState.AUTHENTICATED else AuthenticationState.SIGNED_OUT
            },
            received.map { it.toState },
        )
    }

    private companion object {
        val identityArguments = IdentityArguments(
            userId = "user-1",
            tenantId = "tenant-1",
            platformId = "platform-1",
            roles = setOf("lawyer"),
            permissions = setOf("case:read"),
            authenticatedAt = Instant.parse("2026-07-15T00:00:00Z"),
        )

        fun tokenSet(suffix: String) = AuthTokenSet(
            accessToken = "access-$suffix",
            refreshToken = "refresh-$suffix",
        )
    }
}

private data class IdentityArguments(
    val userId: String,
    val tenantId: String,
    val platformId: String,
    val roles: Set<String>,
    val permissions: Set<String>,
    val authenticatedAt: Instant,
)

private suspend fun AuthSessionManager.login(
    tokens: AuthTokenSet,
    identity: IdentityArguments,
) = login(
    userId = identity.userId,
    tenantId = identity.tenantId,
    platformId = identity.platformId,
    roles = identity.roles,
    permissions = identity.permissions,
    authenticatedAt = identity.authenticatedAt,
    tokens = tokens,
)

private suspend fun refreshWith(
    manager: AuthSessionManager,
    tokens: AuthTokenSet,
    identity: IdentityArguments,
) = manager.refresh(
    userId = identity.userId,
    tenantId = identity.tenantId,
    platformId = identity.platformId,
    roles = identity.roles,
    permissions = identity.permissions,
    authenticatedAt = identity.authenticatedAt,
    tokens = tokens,
)

private class RecordingCredentialPersistence(
    private val onClear: () -> Unit = {},
    private val loaded: AuthTokenSet? = null,
) : AuthCredentialPersistencePort {
    var failReplace: Boolean = false
    var failClear: Boolean = false
    var replaced: AuthTokenSet? = null
    var clearCount: Int = 0
    var loadCount: Int = 0

    override suspend fun load(): AuthTokenSet? {
        loadCount += 1
        return loaded
    }

    override suspend fun replace(tokens: AuthTokenSet) {
        if (failReplace) error("replace failed")
        replaced = tokens
    }

    override suspend fun clear() {
        if (failClear) error("clear failed")
        clearCount += 1
        onClear()
    }
}
