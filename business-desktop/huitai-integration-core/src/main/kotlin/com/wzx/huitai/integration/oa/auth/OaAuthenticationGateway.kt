package com.wzx.huitai.integration.oa.auth

import io.ktor.client.engine.HttpClientEngine
import java.util.concurrent.atomic.AtomicBoolean

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

/** 持有唯一 OA 认证客户端，并只向调用方暴露两个不可跨转型的窄端口。 */
class OaAuthenticationGatewayBundle internal constructor(
    val preAuthentication: OaPreAuthenticationGateway,
    val candidateAuthentication: OaCandidateAuthenticationGateway,
    private val owner: AutoCloseable,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) owner.close()
    }
}

/** 面向 app 装配层的认证客户端所有权工厂。 */
object OaAuthenticationGatewayFactory {
    fun create(
        baseUrl: String,
        apiPrefix: String,
        platformId: Int,
        requestTimeoutMs: Long,
    ): OaAuthenticationGatewayBundle = create(
        baseUrl,
        apiPrefix,
        platformId,
        requestTimeoutMs,
        io.ktor.client.engine.cio.CIO.create(),
    )

    internal fun create(
        baseUrl: String,
        apiPrefix: String,
        platformId: Int,
        requestTimeoutMs: Long,
        engine: HttpClientEngine,
    ): OaAuthenticationGatewayBundle {
        val owner = KtorOaAuthenticationGateway(baseUrl, apiPrefix, platformId, requestTimeoutMs, engine)
        return OaAuthenticationGatewayBundle(
            preAuthentication = PreAuthenticationAdapter(owner),
            candidateAuthentication = CandidateAuthenticationAdapter(owner),
            owner = owner,
        )
    }
}

private class PreAuthenticationAdapter(
    private val delegate: OaPreAuthenticationGateway,
) : OaPreAuthenticationGateway by delegate

private class CandidateAuthenticationAdapter(
    private val delegate: OaCandidateAuthenticationGateway,
) : OaCandidateAuthenticationGateway by delegate
