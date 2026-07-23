package com.wzx.huitai.desktop.auth

import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.integration.auth.TokenRefreshResult
import com.wzx.huitai.integration.oa.auth.OaAuthenticationError
import com.wzx.huitai.integration.oa.auth.OaAuthenticationException
import com.wzx.huitai.integration.oa.auth.OaCandidateAccess
import com.wzx.huitai.integration.oa.auth.OaCandidateAuthenticationGateway
import com.wzx.huitai.integration.oa.auth.OaPreAuthenticationGateway
import java.time.Instant

/** Converts the existing OA refresh/permission protocol into one TokenRefreshCoordinator result. */
internal class OaTokenRefreshAdapter(
    private val preAuthentication: OaPreAuthenticationGateway,
    private val candidateAuthentication: OaCandidateAuthenticationGateway,
    private val sessionManager: AuthSessionManager,
    private val platformId: Int,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun refresh(tenantId: String, refreshToken: String): TokenRefreshResult {
        val current = sessionManager.identity.value ?: return TokenRefreshResult.AuthenticationExpired
        if (current.tenantId != tenantId || current.platformId != platformId.toString()) {
            return TokenRefreshResult.Stale
        }
        val tokens = preAuthentication.refresh(tenantId, refreshToken)
        if (tokens.userId != current.userId) {
            throw OaAuthenticationException(OaAuthenticationError.REMOTE_PROTOCOL_ERROR)
        }
        val permission = candidateAuthentication.loadPermissionInfo(
            OaCandidateAccess(
                userId = tokens.userId,
                tenantId = tenantId,
                platformId = platformId,
                accessToken = tokens.accessToken,
            ),
        )
        if (permission.roles != current.roles || permission.permissions != current.permissions) {
            return TokenRefreshResult.AuthenticationExpired
        }
        return TokenRefreshResult.Refreshed(
            userId = tokens.userId,
            tenantId = tenantId,
            platformId = platformId.toString(),
            roles = permission.roles,
            permissions = permission.permissions,
            authenticatedAt = now(),
            tokens = AuthTokenSet(tokens.accessToken, tokens.refreshToken),
        )
    }
}
