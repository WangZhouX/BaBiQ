package com.wzx.huitai.integration.oa.auth

/** 登录前端口：不读取、也不改变 READY 会话。 */
interface OaPreAuthenticationGateway {
    suspend fun findTenantCandidates(mobile: String): List<OaTenantCandidate>

    suspend fun login(mobileOrEmail: String, password: CharArray, tenantId: String): OaTokenBundle

    suspend fun refresh(tenantId: String, refreshToken: String): OaTokenBundle
}

/** 候选凭据专用端口；token 必须由调用者显式传入。 */
interface OaCandidateAuthenticationGateway {
    suspend fun loadPermissionInfo(candidate: OaCandidateAccess): OaPermissionInfo

    suspend fun logout(candidate: OaCandidateAccess)
}

/** READY 业务边界；普通业务 API 不可经此接口取得候选 token。 */
interface OaAuthenticatedGateway {
    suspend fun logout()
}
