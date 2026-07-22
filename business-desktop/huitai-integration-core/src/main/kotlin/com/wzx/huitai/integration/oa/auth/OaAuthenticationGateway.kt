package com.wzx.huitai.integration.oa.auth

import io.ktor.client.engine.HttpClientEngine

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

/** 面向 app 装配层的窄化工厂；返回对象无法跨端口转型。 */
object OaAuthenticationGatewayFactory {
    fun preAuthentication(
        baseUrl: String,
        apiPrefix: String,
        platformId: Int,
        requestTimeoutMs: Long,
    ): OaPreAuthenticationGateway = preAuthentication(
        baseUrl,
        apiPrefix,
        platformId,
        requestTimeoutMs,
        io.ktor.client.engine.cio.CIO.create(),
    )

    fun candidateAuthentication(
        baseUrl: String,
        apiPrefix: String,
        platformId: Int,
        requestTimeoutMs: Long,
    ): OaCandidateAuthenticationGateway = candidateAuthentication(
        baseUrl,
        apiPrefix,
        platformId,
        requestTimeoutMs,
        io.ktor.client.engine.cio.CIO.create(),
    )

    internal fun preAuthentication(
        baseUrl: String,
        apiPrefix: String,
        platformId: Int,
        requestTimeoutMs: Long,
        engine: HttpClientEngine,
    ): OaPreAuthenticationGateway = PreAuthenticationAdapter(
        KtorOaAuthenticationGateway(baseUrl, apiPrefix, platformId, requestTimeoutMs, engine),
    )

    internal fun candidateAuthentication(
        baseUrl: String,
        apiPrefix: String,
        platformId: Int,
        requestTimeoutMs: Long,
        engine: HttpClientEngine,
    ): OaCandidateAuthenticationGateway = CandidateAuthenticationAdapter(
        KtorOaAuthenticationGateway(baseUrl, apiPrefix, platformId, requestTimeoutMs, engine),
    )
}

private class PreAuthenticationAdapter(
    private val delegate: OaPreAuthenticationGateway,
) : OaPreAuthenticationGateway by delegate

private class CandidateAuthenticationAdapter(
    private val delegate: OaCandidateAuthenticationGateway,
) : OaCandidateAuthenticationGateway by delegate
