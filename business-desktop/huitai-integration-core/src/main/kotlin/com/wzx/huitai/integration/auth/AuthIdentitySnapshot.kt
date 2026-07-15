package com.wzx.huitai.integration.auth

import java.time.Instant
import java.util.Collections

/** 不包含凭据的已认证业务身份快照。 */
class AuthIdentitySnapshot(
    val authSessionId: String,
    val identityEpoch: Long,
    val userId: String,
    val tenantId: String,
    val platformId: String,
    roles: Set<String>,
    permissions: Set<String>,
    val authenticatedAt: Instant,
) {
    val roles: Set<String> = frozenSet(roles)
    val permissions: Set<String> = frozenSet(permissions)

    init {
        require(authSessionId.isNotBlank()) { "authSessionId must not be blank" }
        require(identityEpoch > 0) { "identityEpoch must be positive" }
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(platformId.isNotBlank()) { "platformId must not be blank" }
    }

    /** 复制身份属性时重新经过集合冻结边界。 */
    fun copy(
        authSessionId: String = this.authSessionId,
        identityEpoch: Long = this.identityEpoch,
        userId: String = this.userId,
        tenantId: String = this.tenantId,
        platformId: String = this.platformId,
        roles: Set<String> = this.roles,
        permissions: Set<String> = this.permissions,
        authenticatedAt: Instant = this.authenticatedAt,
    ): AuthIdentitySnapshot = AuthIdentitySnapshot(
        authSessionId = authSessionId,
        identityEpoch = identityEpoch,
        userId = userId,
        tenantId = tenantId,
        platformId = platformId,
        roles = roles,
        permissions = permissions,
        authenticatedAt = authenticatedAt,
    )

    /** 身份快照按全部稳定字段比较。 */
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is AuthIdentitySnapshot &&
            authSessionId == other.authSessionId &&
            identityEpoch == other.identityEpoch &&
            userId == other.userId &&
            tenantId == other.tenantId &&
            platformId == other.platformId &&
            roles == other.roles &&
            permissions == other.permissions &&
            authenticatedAt == other.authenticatedAt

    override fun hashCode(): Int {
        var result = authSessionId.hashCode()
        result = 31 * result + identityEpoch.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + tenantId.hashCode()
        result = 31 * result + platformId.hashCode()
        result = 31 * result + roles.hashCode()
        result = 31 * result + permissions.hashCode()
        result = 31 * result + authenticatedAt.hashCode()
        return result
    }

    override fun toString(): String =
        "AuthIdentitySnapshot(authSessionId=$authSessionId, identityEpoch=$identityEpoch, " +
            "userId=$userId, tenantId=$tenantId, platformId=$platformId, roles=$roles, " +
            "permissions=$permissions, authenticatedAt=$authenticatedAt)"
}

/** 不含凭据的身份边界迁移事件。 */
data class AuthIdentityTransition(
    val previousIdentity: AuthIdentitySnapshot?,
    val currentIdentity: AuthIdentitySnapshot?,
    val identityEpoch: Long,
    val fromState: AuthenticationState,
    val toState: AuthenticationState,
)

/** 创建与调用方 backing 脱钩且 JVM 层不可修改的有序集合快照。 */
internal fun <T> frozenSet(values: Set<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
