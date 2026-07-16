package com.wzx.huitai.agent.application

import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.client.ApplicationSequenceTracker
import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocolValidationException
import com.wzx.huitai.agent.protocol.ApplicationProtocolValidator
import com.wzx.huitai.agent.protocol.ContextEnvelope

/** 当前已认证 Agent 连接的业务上下文发布客户端。 */
class ApplicationContextClient(
    private val rpc: AgentJsonRpcClient,
    private val sequenceTracker: ApplicationSequenceTracker,
) {
    suspend fun publish(envelope: ContextEnvelope) {
        ApplicationProtocolValidator.validate(envelope)
        val payloadBytes = envelope.payload.toString().toByteArray(Charsets.UTF_8)
        if (envelope.payloadSize != payloadBytes.size) {
            throw ApplicationProtocolValidationException("context payloadSize does not match encoded payload")
        }
        ApplicationProtocolValidator.validateContextPayloadSize(payloadBytes)
        // 发送可能已到达对端，因此校验成功后单调推进，不在失败路径回滚。
        sequenceTracker.acceptContextEnvelope(
            candidateDesktopSessionId = envelope.common.desktopSessionId,
            sequence = envelope.common.sequence,
            connectionId = rpc.connectionId,
            contextSequence = envelope.contextSequence,
        )
        rpc.notify(ApplicationMethod.CONTEXT_PUBLISH, envelope)
    }
}
