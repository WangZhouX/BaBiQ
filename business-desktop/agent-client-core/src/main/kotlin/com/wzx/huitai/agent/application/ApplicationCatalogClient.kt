package com.wzx.huitai.agent.application

import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.client.ApplicationSequenceTracker
import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocolValidationException
import com.wzx.huitai.agent.protocol.ApplicationProtocolValidator
import com.wzx.huitai.agent.protocol.CatalogEnvelope

/** 当前已认证 Agent 连接的业务能力目录发布客户端。 */
class ApplicationCatalogClient(
    private val rpc: AgentJsonRpcClient,
    private val sequenceTracker: ApplicationSequenceTracker,
) {
    suspend fun register(envelope: CatalogEnvelope) {
        validate(envelope)
        rpc.request(ApplicationMethod.CATALOG_REGISTER, envelope)
    }

    suspend fun update(envelope: CatalogEnvelope) {
        validate(envelope)
        rpc.notify(ApplicationMethod.CATALOG_UPDATE, envelope)
    }

    private fun validate(envelope: CatalogEnvelope) {
        ApplicationProtocolValidator.validate(envelope)
        val payloadBytes = envelope.payload.toString().toByteArray(Charsets.UTF_8)
        if (envelope.payloadSize != payloadBytes.size) {
            throw ApplicationProtocolValidationException("catalog payloadSize does not match encoded payload")
        }
        ApplicationProtocolValidator.validateCatalogPayloadSize(payloadBytes)
        // 发送结果不明确时也不回滚：旧连接禁止复用水位，新连接仍可重发同一业务 epoch。
        sequenceTracker.acceptCatalogEnvelope(
            candidateDesktopSessionId = envelope.common.desktopSessionId,
            sequence = envelope.common.sequence,
            connectionId = rpc.connectionId,
            catalogEpoch = envelope.catalogEpoch,
        )
    }
}
