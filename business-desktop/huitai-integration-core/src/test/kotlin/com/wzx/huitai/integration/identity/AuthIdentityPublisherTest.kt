package com.wzx.huitai.integration.identity

import com.wzx.huitai.integration.auth.AuthCredentialPersistencePort
import com.wzx.huitai.integration.auth.AuthIdentitySnapshot
import com.wzx.huitai.integration.auth.AuthIdentityTransition
import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.integration.auth.AuthenticationState
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthIdentityPublisherTest {
    @Test
    fun `first login publishes bind`() = runTest {
        val manager = sessionManager()
        val port = RecordingIdentityBindingPort()
        val publisher = AuthIdentityPublisher(manager, port, backgroundScope)
        publisher.start()

        manager.login(tokens = tokenSet("login"), identity = identityArguments)
        runCurrent()

        val bind = port.singleBind()
        val identity = assertNotNull(bind.identity)
        assertEquals(identity.identityEpoch, bind.identityEpoch)
        assertEquals("user-1", identity.userId)
        assertEquals("tenant-1", identity.tenantId)
        assertTrue(port.updates.isEmpty())
        publisher.close()
    }

    @Test
    fun `tenant and user changes publish increasing updates with rotated auth sessions`() = runTest {
        val manager = sessionManager()
        val port = RecordingIdentityBindingPort()
        val publisher = AuthIdentityPublisher(manager, port, backgroundScope)
        publisher.start()
        manager.login(tokens = tokenSet("login"), identity = identityArguments)
        runCurrent()
        val login = assertNotNull(port.singleBind().identity)

        manager.refresh(
            tokens = tokenSet("tenant-change"),
            identity = identityArguments.copy(tenantId = "tenant-2"),
        )
        runCurrent()
        manager.refresh(
            tokens = tokenSet("user-change"),
            identity = identityArguments.copy(userId = "user-2", tenantId = "tenant-2"),
        )
        runCurrent()

        assertEquals(2, port.updates.size)
        val tenantUpdate = assertNotNull(port.updates[0].identity)
        val userUpdate = assertNotNull(port.updates[1].identity)
        assertEquals(login.identityEpoch + 1, tenantUpdate.identityEpoch)
        assertEquals(tenantUpdate.identityEpoch + 1, userUpdate.identityEpoch)
        assertNotEquals(login.authSessionId, tenantUpdate.authSessionId)
        assertNotEquals(tenantUpdate.authSessionId, userUpdate.authSessionId)
        assertEquals("tenant-2", tenantUpdate.tenantId)
        assertEquals("user-2", userUpdate.userId)
        publisher.close()
    }

    @Test
    fun `unchanged user tenant boundary refresh does not publish identity message`() = runTest {
        val manager = sessionManager()
        val port = RecordingIdentityBindingPort()
        val publisher = AuthIdentityPublisher(manager, port, backgroundScope)
        publisher.start()
        manager.login(tokens = tokenSet("login"), identity = identityArguments)
        runCurrent()
        val original = assertNotNull(port.singleBind().identity)

        manager.refresh(
            tokens = tokenSet("refreshed"),
            identity = identityArguments.copy(
                platformId = "platform-2",
                roles = setOf("partner"),
                permissions = setOf("case:read", "case:write"),
                authenticatedAt = Instant.parse("2026-07-16T01:00:00Z"),
            ),
        )
        runCurrent()

        val refreshed = assertNotNull(manager.identity.value)
        assertEquals(original.authSessionId, refreshed.authSessionId)
        assertEquals(original.identityEpoch, refreshed.identityEpoch)
        assertEquals("platform-2", refreshed.platformId)
        assertEquals(setOf("partner"), refreshed.roles)
        assertEquals(setOf("case:read", "case:write"), refreshed.permissions)
        assertEquals(1, port.binds.size)
        assertTrue(port.updates.isEmpty())
        publisher.close()
    }

    @Test
    fun `logout publishes signed out update before business disappearance hook`() = runTest {
        val events = mutableListOf<String>()
        val manager = sessionManager()
        val port = RecordingIdentityBindingPort(onUpdate = { events += "signed-out-update" })
        val publisher = AuthIdentityPublisher(
            authSessionManager = manager,
            bindingPort = port,
            scope = backgroundScope,
            onSignedOutPublished = { events += "business-disappeared" },
        )
        publisher.start()
        manager.login(tokens = tokenSet("login"), identity = identityArguments)
        runCurrent()
        val loginEpoch = port.singleBind().identityEpoch

        manager.logout()
        runCurrent()

        assertEquals(listOf("signed-out-update", "business-disappeared"), events)
        val signedOut = port.updates.single()
        assertNull(signedOut.identity)
        assertEquals(loginEpoch + 1, signedOut.identityEpoch)
        publisher.close()
    }

    @Test
    fun `start reconciles authoritative authenticated state when transition had no subscriber`() = runTest {
        val manager = sessionManager()
        manager.login(tokens = tokenSet("before-start"), identity = identityArguments)
        val authoritative = assertNotNull(manager.identity.value)
        val port = RecordingIdentityBindingPort()
        val publisher = AuthIdentityPublisher(manager, port, backgroundScope)

        publisher.start()
        runCurrent()

        assertEquals(authoritative, assertNotNull(port.singleBind().identity))
        assertTrue(port.updates.isEmpty())
        publisher.close()
    }

    @Test
    fun `failed authoritative bind can be retried without installing duplicate collectors`() = runTest {
        val manager = sessionManager()
        manager.login(tokens = tokenSet("before-start"), identity = identityArguments)
        val port = RecordingIdentityBindingPort().apply { failNextBind = true }
        val publisher = AuthIdentityPublisher(manager, port, backgroundScope)

        kotlin.test.assertFailsWith<IllegalStateException> { publisher.start() }
        publisher.start()
        runCurrent()
        manager.refresh(
            tokens = tokenSet("tenant-change"),
            identity = identityArguments.copy(tenantId = "tenant-2"),
        )
        runCurrent()

        assertEquals(1, port.binds.size)
        assertEquals(1, port.updates.size)
        assertEquals("tenant-2", assertNotNull(port.updates.single().identity).tenantId)
        publisher.close()
    }

    @Test
    fun `stale and non monotonic publications are rejected locally`() = runTest {
        val manager = sessionManager()
        val port = RecordingIdentityBindingPort()
        val publisher = AuthIdentityPublisher(manager, port, backgroundScope)
        publisher.start()
        manager.login(tokens = tokenSet("login"), identity = identityArguments)
        runCurrent()
        val current = assertNotNull(port.singleBind().identity)

        val sameEpochDifferentSession = current.copy(authSessionId = "forged-session")
        val sameEpochAccepted = publisher.publish(
            AuthIdentityTransition(
                previousIdentity = current,
                currentIdentity = sameEpochDifferentSession,
                identityEpoch = current.identityEpoch,
                fromState = AuthenticationState.SWITCHING_TENANT,
                toState = AuthenticationState.AUTHENTICATED,
            ),
        )
        val staleSignedOutAccepted = publisher.publish(
            AuthIdentityTransition(
                previousIdentity = current,
                currentIdentity = null,
                identityEpoch = current.identityEpoch,
                fromState = AuthenticationState.AUTHENTICATED,
                toState = AuthenticationState.SIGNED_OUT,
            ),
        )

        assertFalse(sameEpochAccepted)
        assertFalse(staleSignedOutAccepted)
        assertEquals(1, port.binds.size)
        assertTrue(port.updates.isEmpty())
        publisher.close()
    }

    private fun sessionManager() = AuthSessionManager(
        credentialPersistence = InMemoryIdentityCredentials(),
        authSessionIdFactory = { "auth-${nextSessionId++}" },
    )

    private companion object {
        var nextSessionId = 1

        val identityArguments = IdentityArguments(
            userId = "user-1",
            tenantId = "tenant-1",
            platformId = "platform-1",
            roles = setOf("lawyer"),
            permissions = setOf("case:read"),
            authenticatedAt = Instant.parse("2026-07-16T00:00:00Z"),
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

private suspend fun AuthSessionManager.login(tokens: AuthTokenSet, identity: IdentityArguments) = login(
    userId = identity.userId,
    tenantId = identity.tenantId,
    platformId = identity.platformId,
    roles = identity.roles,
    permissions = identity.permissions,
    authenticatedAt = identity.authenticatedAt,
    tokens = tokens,
)

private suspend fun AuthSessionManager.refresh(tokens: AuthTokenSet, identity: IdentityArguments) = refresh(
    userId = identity.userId,
    tenantId = identity.tenantId,
    platformId = identity.platformId,
    roles = identity.roles,
    permissions = identity.permissions,
    authenticatedAt = identity.authenticatedAt,
    tokens = tokens,
)

private class RecordingIdentityBindingPort(
    private val onUpdate: suspend (AuthIdentityBinding) -> Unit = {},
) : IdentityBindingPort {
    var failNextBind: Boolean = false
    val binds = mutableListOf<AuthIdentityBinding>()
    val updates = mutableListOf<AuthIdentityBinding>()

    override suspend fun bind(binding: AuthIdentityBinding) {
        if (failNextBind) {
            failNextBind = false
            error("bind failed")
        }
        binds += binding
    }

    override suspend fun update(binding: AuthIdentityBinding) {
        updates += binding
        onUpdate(binding)
    }

    fun singleBind(): AuthIdentityBinding = binds.single()
}

private class InMemoryIdentityCredentials : AuthCredentialPersistencePort {
    private var tokens: AuthTokenSet? = null

    override suspend fun load(): AuthTokenSet? = tokens

    override suspend fun replace(tokens: AuthTokenSet) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}
