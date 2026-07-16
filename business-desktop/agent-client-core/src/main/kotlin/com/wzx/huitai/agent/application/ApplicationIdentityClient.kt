package com.wzx.huitai.agent.application

import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.client.ApplicationSequenceTracker
import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocolValidator
import com.wzx.huitai.agent.protocol.CatalogEnvelope
import com.wzx.huitai.agent.protocol.ContextEnvelope
import com.wzx.huitai.agent.protocol.IdentityEnvelope

/** 供 app composition bridge 注入的最小身份发布端口。 */
interface ApplicationIdentityPublisherClient {
    suspend fun bind(envelope: IdentityEnvelope)
    suspend fun update(envelope: IdentityEnvelope)
}

/** 身份发布与连接首轮注册顺序的唯一编排入口。 */
class ApplicationIdentityClient(
    private val rpc: AgentJsonRpcClient,
    private val sequenceTracker: ApplicationSequenceTracker,
    private val catalogClient: ApplicationCatalogClient,
    private val contextClient: ApplicationContextClient,
) : ApplicationIdentityPublisherClient {
    suspend fun registerAuthenticatedConnection(
        identity: IdentityEnvelope,
        catalog: CatalogEnvelope,
        context: ContextEnvelope,
        afterRegistration: suspend () -> Unit = {},
    ) {
        require(identity.authenticated) { "Authenticated connection requires authenticated identity" }
        validateRegistrationScope(identity, catalog, context)
        bind(identity)
        catalogClient.register(catalog)
        contextClient.publish(context)
        afterRegistration()
    }

    override suspend fun bind(envelope: IdentityEnvelope) {
        require(envelope.authenticated) { "Identity bind must be authenticated" }
        validate(envelope)
        rpc.request(ApplicationMethod.IDENTITY_BIND, envelope)
    }

    override suspend fun update(envelope: IdentityEnvelope) {
        validate(envelope)
        rpc.notify(ApplicationMethod.IDENTITY_UPDATE, envelope)
    }

    suspend fun signOut(identity: IdentityEnvelope, afterPublished: suspend () -> Unit) {
        require(!identity.authenticated) { "Sign out requires signed-out identity" }
        update(identity)
        afterPublished()
    }

    private fun validateRegistrationScope(
        identity: IdentityEnvelope,
        catalog: CatalogEnvelope,
        context: ContextEnvelope,
    ) {
        val expected = identity.common.registrationScope()
        if (catalog.common.registrationScope() != expected || context.common.registrationScope() != expected) {
            throw com.wzx.huitai.agent.protocol.ApplicationProtocolValidationException(
                "Registration envelopes must share the same identity scope",
            )
        }
        if (catalog.catalogEpoch != context.catalogEpoch || catalog.contextSequence != context.contextSequence) {
            throw com.wzx.huitai.agent.protocol.ApplicationProtocolValidationException(
                "Catalog and context registration versions must match",
            )
        }
    }

    private fun com.wzx.huitai.agent.protocol.CommonApplicationFields.registrationScope() = listOf(
        protocolVersion,
        desktopInstanceId,
        desktopSessionId,
        authSessionId,
        identityEpoch,
        userId,
        tenantId,
        platformId,
    )

    private fun validate(envelope: IdentityEnvelope) {
        ApplicationProtocolValidator.validate(envelope)
        // send 抛错不代表对端未收到；同连接水位保持单调，新连接拥有独立 republish 水位。
        sequenceTracker.acceptIdentityEnvelope(
            candidateDesktopSessionId = envelope.common.desktopSessionId,
            sequence = envelope.common.sequence,
            connectionId = rpc.connectionId,
            identityEpoch = envelope.common.identityEpoch,
        )
    }
}
