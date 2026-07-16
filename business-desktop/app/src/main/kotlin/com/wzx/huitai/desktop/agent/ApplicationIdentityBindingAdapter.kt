package com.wzx.huitai.desktop.agent

import com.wzx.huitai.agent.application.ApplicationIdentityPublisherClient
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.CommonApplicationFields
import com.wzx.huitai.agent.protocol.IdentityEnvelope
import com.wzx.huitai.integration.identity.AuthIdentityBinding
import com.wzx.huitai.integration.identity.IdentityBindingPort
import java.time.Instant

/** app composition 层唯一的 integration identity 到 Agent application identity 桥。 */
class ApplicationIdentityBindingAdapter(
    private val delegate: ApplicationIdentityPublisherClient,
    private val desktopSessionIdentity: DesktopSessionIdentity,
    private val nextSequence: () -> Long,
    private val now: () -> Instant,
) : IdentityBindingPort {
    override suspend fun bind(binding: AuthIdentityBinding) {
        delegate.bind(binding.toEnvelope())
    }

    override suspend fun update(binding: AuthIdentityBinding) {
        delegate.update(binding.toEnvelope())
    }

    private fun AuthIdentityBinding.toEnvelope(): IdentityEnvelope {
        val current = identity
        return IdentityEnvelope(
            common = CommonApplicationFields(
                protocolVersion = ApplicationProtocol.PROTOCOL_VERSION,
                desktopInstanceId = desktopSessionIdentity.desktopInstanceId,
                desktopSessionId = desktopSessionIdentity.desktopSessionId,
                authSessionId = current?.authSessionId,
                identityEpoch = identityEpoch,
                sequence = nextSequence(),
                generatedAt = now().toString(),
                userId = current?.userId,
                tenantId = current?.tenantId,
                platformId = current?.platformId,
            ),
            authenticated = current != null,
            roles = current?.roles ?: emptySet(),
            permissions = current?.permissions ?: emptySet(),
        )
    }
}
