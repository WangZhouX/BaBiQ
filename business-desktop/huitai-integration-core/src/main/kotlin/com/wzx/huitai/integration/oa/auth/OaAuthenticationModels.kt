package com.wzx.huitai.integration.oa.auth

import java.util.Collections

data class OaTenantCandidate(
    val userId: String,
    val tenantId: String,
    val platformId: Int,
    val tenantName: String? = null,
    val tenantEnterStatus: Int,
    val tenantEnterId: String? = null,
) {
    init {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
    }
}

/** 登录或刷新阶段使用的 token 包；字符串输出始终脱敏。 */
class OaTokenBundle(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val expiresTime: Long,
) {
    init {
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
        require(userId.isNotBlank()) { "userId must not be blank" }
    }

    override fun toString(): String =
        "OaTokenBundle(accessToken=[REDACTED], refreshToken=[REDACTED], userId=$userId, expiresTime=$expiresTime)"
}

/** 未提交至 AuthSessionManager 前的显式候选认证边界。 */
class OaCandidateAccess(
    val userId: String,
    val tenantId: String,
    val platformId: Int,
    val accessToken: String,
) {
    init {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
    }

    override fun toString(): String =
        "OaCandidateAccess(userId=$userId, tenantId=$tenantId, platformId=$platformId, accessToken=[REDACTED])"
}

data class OaPermissionUser(
    val id: String,
    val name: String? = null,
)

class OaPermissionInfo(
    permissions: Set<String>,
    roles: Set<String>,
    val user: OaPermissionUser,
    val menus: List<Any?>,
) {
    val permissions: Set<String> = Collections.unmodifiableSet(LinkedHashSet(permissions))
    val roles: Set<String> = Collections.unmodifiableSet(LinkedHashSet(roles))
}
