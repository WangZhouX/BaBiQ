package com.wzx.huitai.agent.business.auth

enum class BusinessAuthStatus {
    SIGNED_OUT, DETACHED, AUTHENTICATING, RESTORING, INSTALLING, READY, REVOKING, REVOKED, UNKNOWN,
}

data class BusinessUserSummary(
    val id: String,
    val name: String,
    val avatar: String? = null,
) {
    override fun toString(): String = "BusinessUserSummary(id=[REDACTED], name=[REDACTED], avatar=${if (avatar == null) "null" else "[REDACTED]"})"
}

data class BusinessTenantSummary(
    val id: String,
    val name: String,
) {
    override fun toString(): String = "BusinessTenantSummary(id=[REDACTED], name=[REDACTED])"
}

data class BusinessNavigationTarget(
    val kind: String,
    val path: String,
    val title: String,
)

data class BusinessSessionView(
    val status: BusinessAuthStatus,
    val authSessionId: String? = null,
    val identityEpoch: Long,
    val generation: Long,
    val platformId: String? = null,
    val user: BusinessUserSummary? = null,
    val tenant: BusinessTenantSummary? = null,
    val roles: Set<String> = emptySet(),
    val permissions: Set<String> = emptySet(),
    val menus: List<BusinessNavigationTarget> = emptyList(),
    val rememberedAccount: String? = null,
    val attachHandle: String? = null,
) {
    init {
        require(identityEpoch >= 0) { "identityEpoch must not be negative" }
        if (status == BusinessAuthStatus.READY) {
            require(identityEpoch > 0) { "READY identityEpoch must be positive" }
            require(!authSessionId.isNullOrBlank()) { "READY authSessionId must be present" }
        }
        require(generation >= 0) { "generation must not be negative" }
    }

    override fun toString(): String =
        "BusinessSessionView(status=$status, identityEpoch=$identityEpoch, generation=$generation, platformId=${platformId?.let { "[REDACTED]" }}, user=${user?.let { "[REDACTED]" }}, tenant=${tenant?.let { "[REDACTED]" }}, roles=$roles, permissions=$permissions, menus=${menus.size}, rememberedAccount=${rememberedAccount?.let { "[REDACTED]" }}, attachHandle=${attachHandle?.let { "[REDACTED]" }})"
}

data class BusinessTenantCandidate(
    val candidateId: String,
    val name: String,
    val status: String,
    val platformId: Int = 0,
    val tenantEnterStatus: Int = 0,
)
