package com.wzx.huitai.desktop.auth

import com.wzx.huitai.integration.auth.AuthCredentialPersistencePort
import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.integration.auth.TokenRefreshCoordinator
import com.wzx.huitai.integration.auth.TokenRefreshResult
import com.wzx.huitai.integration.oa.auth.OaCandidateAccess
import com.wzx.huitai.integration.oa.auth.OaCandidateAuthenticationGateway
import com.wzx.huitai.integration.oa.auth.OaPermissionInfo
import com.wzx.huitai.integration.oa.auth.OaPermissionUser
import com.wzx.huitai.integration.oa.auth.OaPreAuthenticationGateway
import com.wzx.huitai.integration.oa.auth.OaTenantCandidate
import com.wzx.huitai.integration.oa.auth.OaTokenBundle
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class OaTokenRefreshAdapterTest {
    @Test
    fun `revoked role expires authentication without installing refreshed token`() = runTest {
        val scenario = scenario(
            oldRoles = setOf("lawyer", "reviewer"),
            newRoles = setOf("lawyer"),
        )

        val result = scenario.adapter.refresh(TENANT_ID, OLD_REFRESH_TOKEN)

        assertSame(TokenRefreshResult.AuthenticationExpired, result)
        scenario.assertOldAuthorityUnchanged(setOf("lawyer", "reviewer"), BASE_PERMISSIONS)
    }

    @Test
    fun `revoked permission expires authentication without installing refreshed token`() = runTest {
        val oldPermissions = setOf("case:read", "case:write")
        val scenario = scenario(
            oldPermissions = oldPermissions,
            newPermissions = setOf("case:read"),
        )

        val result = scenario.adapter.refresh(TENANT_ID, OLD_REFRESH_TOKEN)

        assertSame(TokenRefreshResult.AuthenticationExpired, result)
        scenario.assertOldAuthorityUnchanged(BASE_ROLES, oldPermissions)
    }

    @Test
    fun `added permission expires authentication without installing refreshed token`() = runTest {
        val scenario = scenario(newPermissions = BASE_PERMISSIONS + "case:write")

        val result = scenario.adapter.refresh(TENANT_ID, OLD_REFRESH_TOKEN)

        assertSame(TokenRefreshResult.AuthenticationExpired, result)
        scenario.assertOldAuthorityUnchanged(BASE_ROLES, BASE_PERMISSIONS)
    }

    @Test
    fun `unchanged authority lets coordinator install refreshed token`() = runTest {
        val scenario = scenario()
        val coordinator = TokenRefreshCoordinator(
            sessionManager = scenario.sessionManager,
            refreshScope = this,
            refreshOperation = scenario.adapter::refresh,
        )

        val result = assertIs<TokenRefreshResult.Refreshed>(
            coordinator.refreshOnce(),
        )

        assertEquals(USER_ID, result.userId)
        assertEquals(BASE_ROLES, result.roles)
        assertEquals(BASE_PERMISSIONS, result.permissions)
        assertEquals(NEW_TOKENS, result.tokens)
        assertEquals(BASE_ROLES, scenario.sessionManager.identity.value?.roles)
        assertEquals(BASE_PERMISSIONS, scenario.sessionManager.identity.value?.permissions)
        assertEquals(listOf(OLD_TOKENS, NEW_TOKENS), scenario.persistence.replacements)
    }

    private suspend fun scenario(
        oldRoles: Set<String> = BASE_ROLES,
        oldPermissions: Set<String> = BASE_PERMISSIONS,
        newRoles: Set<String> = oldRoles,
        newPermissions: Set<String> = oldPermissions,
    ): Scenario {
        val persistence = RecordingCredentialPersistence()
        val sessionManager = AuthSessionManager(
            credentialPersistence = persistence,
            authSessionIdFactory = { "auth-session-1" },
            identityEpochFactory = { 1 },
        )
        sessionManager.login(
            userId = USER_ID,
            tenantId = TENANT_ID,
            platformId = PLATFORM_ID.toString(),
            roles = oldRoles,
            permissions = oldPermissions,
            authenticatedAt = Instant.parse("2026-07-23T00:00:00Z"),
            tokens = OLD_TOKENS,
        )
        val adapter = OaTokenRefreshAdapter(
            preAuthentication = RefreshGateway(),
            candidateAuthentication = PermissionGateway(newRoles, newPermissions),
            sessionManager = sessionManager,
            platformId = PLATFORM_ID,
            now = { Instant.parse("2026-07-23T00:01:00Z") },
        )
        return Scenario(adapter, sessionManager, persistence)
    }

    private data class Scenario(
        val adapter: OaTokenRefreshAdapter,
        val sessionManager: AuthSessionManager,
        val persistence: RecordingCredentialPersistence,
    ) {
        fun assertOldAuthorityUnchanged(expectedRoles: Set<String>, expectedPermissions: Set<String>) {
            val identity = requireNotNull(sessionManager.identity.value)
            assertEquals(expectedRoles, identity.roles)
            assertEquals(expectedPermissions, identity.permissions)
            assertEquals(listOf(OLD_TOKENS), persistence.replacements)
        }
    }

    private class RefreshGateway : OaPreAuthenticationGateway {
        override suspend fun findTenantCandidates(mobile: String): List<OaTenantCandidate> = error("unused")
        override suspend fun login(mobileOrEmail: String, password: CharArray, tenantId: String): OaTokenBundle =
            error("unused")

        override suspend fun refresh(tenantId: String, refreshToken: String): OaTokenBundle {
            assertEquals(TENANT_ID, tenantId)
            assertEquals(OLD_REFRESH_TOKEN, refreshToken)
            return OaTokenBundle(
                accessToken = NEW_TOKENS.accessToken,
                refreshToken = NEW_TOKENS.refreshToken,
                userId = USER_ID,
                expiresTime = 4_102_444_800_000,
            )
        }
    }

    private class PermissionGateway(
        private val roles: Set<String>,
        private val permissions: Set<String>,
    ) : OaCandidateAuthenticationGateway {
        override suspend fun loadPermissionInfo(candidate: OaCandidateAccess): OaPermissionInfo {
            assertEquals(USER_ID, candidate.userId)
            assertEquals(NEW_TOKENS.accessToken, candidate.accessToken)
            return OaPermissionInfo(
                permissions = permissions,
                roles = roles,
                user = OaPermissionUser(USER_ID, "Lawyer"),
                menus = emptyList(),
            )
        }

        override suspend fun logout(candidate: OaCandidateAccess) = Unit
    }

    private class RecordingCredentialPersistence : AuthCredentialPersistencePort {
        val replacements = mutableListOf<AuthTokenSet>()
        override suspend fun load(): AuthTokenSet? = replacements.lastOrNull()
        override suspend fun replace(tokens: AuthTokenSet) {
            replacements += tokens
        }
        override suspend fun clear() {
            replacements.clear()
        }
    }

    private companion object {
        const val USER_ID = "user-1"
        const val TENANT_ID = "tenant-1"
        const val PLATFORM_ID = 1
        const val OLD_REFRESH_TOKEN = "refresh-old"
        val BASE_ROLES = setOf("lawyer")
        val BASE_PERMISSIONS = setOf("case:read")
        val OLD_TOKENS = AuthTokenSet("access-old", OLD_REFRESH_TOKEN)
        val NEW_TOKENS = AuthTokenSet("access-new", "refresh-new")
    }
}
