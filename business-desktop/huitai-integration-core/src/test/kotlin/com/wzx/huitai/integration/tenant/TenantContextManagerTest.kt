package com.wzx.huitai.integration.tenant

import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.integration.auth.AuthCredentialPersistencePort
import com.wzx.huitai.integration.auth.AuthIdentitySnapshot
import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.integration.identity.IdentityBoundaryActionPort
import com.wzx.huitai.integration.permission.PermissionSnapshot
import com.wzx.huitai.integration.permission.PermissionSnapshotProvider
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TenantContextManagerTest {
    @Test
    fun `cleanup hooks are mandatory constructor dependencies`() {
        val hasDefaultArgumentConstructor = TenantContextManager::class.java.declaredConstructors.any { constructor ->
            constructor.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker"
        }

        assertEquals(false, hasDefaultArgumentConstructor)
    }

    @Test
    fun `successful tenant switch cleans old boundary before publishing new identity`() = runTest {
        val events = mutableListOf<String>()
        val session = authenticatedSession()
        val oldIdentity = assertNotNull(session.identity.value)
        val actions = RecordingBoundaryActions(events)
        val manager = TenantContextManager(
            authSessionManager = session,
            permissionSnapshotProvider = FixedPermissionProvider(
                PermissionSnapshot(
                    roles = setOf("partner"),
                    permissions = setOf("case:read", "case:write"),
                ),
            ),
            actionBoundary = actions,
            desktopInstanceId = "desktop-1",
            desktopSessionId = "process-1",
            clearPageContext = { scope ->
                assertEquals(oldIdentity.toScope(), scope)
                events += "clear-page"
            },
            clearUnappliedPatches = { scope ->
                assertEquals(oldIdentity.toScope(), scope)
                events += "clear-patches"
            },
        )

        val newIdentity = manager.switchTenant(
            TenantSwitchRequest(
                userId = "user-1",
                tenantId = "tenant-2",
                platformId = "platform-1",
                authenticatedAt = Instant.parse("2026-07-16T01:00:00Z"),
                tokens = tokenSet("tenant-2"),
            ),
        )

        assertEquals(
            listOf("clear-page", "clear-patches", "cancel-pre-execution", "detach-executing"),
            events,
        )
        assertNotEquals(oldIdentity.authSessionId, newIdentity.authSessionId)
        assertEquals(oldIdentity.identityEpoch + 1, newIdentity.identityEpoch)
        assertEquals("tenant-2", newIdentity.tenantId)
        assertEquals(setOf("partner"), newIdentity.roles)
        assertEquals(setOf("case:read", "case:write"), newIdentity.permissions)
        assertEquals(
            setOf(
                ActionExecutionState.RECEIVED,
                ActionExecutionState.VALIDATING,
                ActionExecutionState.PREVIEWED,
                ActionExecutionState.WAITING_APPROVAL,
            ),
            actions.canceledStates,
        )
        assertEquals(oldIdentity.toScope(), actions.detachedScope)
    }

    @Test
    fun `cleanup failure keeps old authoritative identity and does not continue boundary work`() = runTest {
        val events = mutableListOf<String>()
        val session = authenticatedSession()
        val oldIdentity = assertNotNull(session.identity.value)
        val actions = RecordingBoundaryActions(events)
        val manager = TenantContextManager(
            authSessionManager = session,
            permissionSnapshotProvider = FixedPermissionProvider(
                PermissionSnapshot(setOf("partner"), setOf("case:write")),
            ),
            actionBoundary = actions,
            desktopInstanceId = "desktop-1",
            desktopSessionId = "process-1",
            clearPageContext = { events += "clear-page" },
            clearUnappliedPatches = {
                events += "clear-patches"
                error("patch cleanup failed")
            },
        )

        assertFailsWith<IllegalStateException> {
            manager.switchTenant(
                TenantSwitchRequest(
                    userId = "user-1",
                    tenantId = "tenant-2",
                    platformId = "platform-1",
                    authenticatedAt = Instant.parse("2026-07-16T01:00:00Z"),
                    tokens = tokenSet("tenant-2"),
                ),
            )
        }

        assertEquals(oldIdentity, session.identity.value)
        assertEquals(listOf("clear-page", "clear-patches"), events)
        assertNull(actions.detachedScope)
    }

    @Test
    fun `executing result remains available only through exact old complete scope`() = runTest {
        val session = authenticatedSession()
        val oldIdentity = assertNotNull(session.identity.value)
        val oldScope = oldIdentity.toScope()
        val expected = ActionResult.Success(
            executionId = "execution-1",
            output = JsonPrimitive("completed-under-old-tenant"),
        )
        val actions = RecordingBoundaryActions(
            events = mutableListOf(),
            results = mapOf(("execution-1" to oldScope) to expected),
        )
        val manager = TenantContextManager(
            authSessionManager = session,
            permissionSnapshotProvider = FixedPermissionProvider(
                PermissionSnapshot(setOf("partner"), setOf("case:write")),
            ),
            actionBoundary = actions,
            desktopInstanceId = "desktop-1",
            desktopSessionId = "process-1",
            clearPageContext = {},
            clearUnappliedPatches = {},
        )

        val newIdentity = manager.switchTenant(
            TenantSwitchRequest(
                userId = "user-1",
                tenantId = "tenant-2",
                platformId = "platform-1",
                authenticatedAt = Instant.parse("2026-07-16T01:00:00Z"),
                tokens = tokenSet("tenant-2"),
            ),
        )

        assertEquals(expected, manager.result("execution-1", oldScope))
        assertNull(manager.result("execution-1", newIdentity.toScope()))
        assertNull(manager.result("execution-1", oldScope.copy(tenantId = "tenant-2")))
        assertNull(manager.result("execution-1", oldScope.copy(authSessionId = newIdentity.authSessionId)))
        assertNull(manager.result("execution-1", oldScope.copy(identityEpoch = newIdentity.identityEpoch)))
        assertNull(manager.result("execution-1", oldScope.copy(userId = "user-2")))
        assertNull(manager.result("execution-1", oldScope.copy(platformId = "platform-2")))
        assertNull(manager.result("execution-1", oldScope.copy(desktopInstanceId = "desktop-2")))
        assertNull(manager.result("execution-1", oldScope.copy(desktopSessionId = "process-2")))
    }

    private suspend fun authenticatedSession(): AuthSessionManager =
        AuthSessionManager(InMemoryCredentials(), authSessionIdFactory = { "auth-${nextSessionId++}" }).also {
            it.login(
                userId = "user-1",
                tenantId = "tenant-1",
                platformId = "platform-1",
                roles = setOf("lawyer"),
                permissions = setOf("case:read"),
                authenticatedAt = Instant.parse("2026-07-16T00:00:00Z"),
                tokens = tokenSet("tenant-1"),
            )
        }

    private fun AuthIdentitySnapshot.toScope() = ActionIdentityScope(
        desktopInstanceId = "desktop-1",
        desktopSessionId = "process-1",
        authSessionId = authSessionId,
        identityEpoch = identityEpoch,
        userId = userId,
        tenantId = tenantId,
        platformId = platformId,
    )

    private companion object {
        var nextSessionId = 1

        fun tokenSet(suffix: String) = AuthTokenSet("access-$suffix", "refresh-$suffix")
    }
}

private class FixedPermissionProvider(
    private val snapshot: PermissionSnapshot,
) : PermissionSnapshotProvider {
    override suspend fun load(userId: String, tenantId: String, platformId: String): PermissionSnapshot = snapshot
}

private class RecordingBoundaryActions(
    private val events: MutableList<String>,
    private val results: Map<Pair<String, ActionIdentityScope>, ActionResult<JsonElement>> = emptyMap(),
) : IdentityBoundaryActionPort {
    var canceledStates: Set<ActionExecutionState>? = null
    var detachedScope: ActionIdentityScope? = null

    override suspend fun cancelPreExecution(
        identityScope: ActionIdentityScope,
        states: Set<ActionExecutionState>,
    ) {
        events += "cancel-pre-execution"
        canceledStates = states
    }

    override suspend fun detachExecutingForReconciliation(identityScope: ActionIdentityScope) {
        events += "detach-executing"
        detachedScope = identityScope
    }

    override suspend fun result(
        executionId: String,
        identityScope: ActionIdentityScope,
    ): ActionResult<JsonElement>? = results[executionId to identityScope]
}

private class InMemoryCredentials : AuthCredentialPersistencePort {
    private var tokens: AuthTokenSet? = null

    override suspend fun load(): AuthTokenSet? = tokens

    override suspend fun replace(tokens: AuthTokenSet) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}
