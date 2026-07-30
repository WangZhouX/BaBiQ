package com.wzx.huitai.desktop.runtime

import com.wzx.huitai.agent.client.AgentConnectRequest
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessAgentDevelopmentSessionFileTest {
    @Test
    fun `publisher exposes one authenticated loopback request and removes it on close`() {
        val paths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-dev-session"))
        val request = connectRequest()
        val ownership = BusinessAgentDevelopmentSessionFile.acquireOwnership(paths.agentDevelopmentSession)

        val published = BusinessAgentDevelopmentSessionFile.publish(
            paths.agentDevelopmentSession,
            request,
            ownership,
        )
        val loaded = BusinessAgentDevelopmentSessionFile.read(paths.agentDevelopmentSession)

        assertEquals(request.url, loaded.url)
        assertEquals(request.identity.desktopInstanceId, loaded.identity.desktopInstanceId)
        assertEquals(request.identity.desktopSessionId, loaded.identity.desktopSessionId)
        assertEquals(request.identity.desktopSessionToken, loaded.identity.desktopSessionToken)
        assertEquals(request.identity.localOrigin, loaded.identity.localOrigin)
        assertTrue(Files.isRegularFile(paths.agentDevelopmentSession))
        assertFalse(published.toString().contains(request.identity.desktopSessionToken))

        published.close()
        ownership.close()
        assertFalse(Files.exists(paths.agentDevelopmentSession))
    }

    @Test
    fun `reader rejects a remote backend URL without exposing the token`() {
        val paths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-remote-dev-session"))
        val request = connectRequest()
        Files.writeString(
            paths.agentDevelopmentSession,
            """
            {
              "url": "ws://example.com:49391/ws/agent",
              "desktopInstanceId": "${request.identity.desktopInstanceId}",
              "desktopSessionId": "${request.identity.desktopSessionId}",
              "desktopSessionToken": "${request.identity.desktopSessionToken}",
              "localOrigin": "http://127.0.0.1"
            }
            """.trimIndent(),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            BusinessAgentDevelopmentSessionFile.read(paths.agentDevelopmentSession)
        }

        assertFalse(failure.message.orEmpty().contains(request.identity.desktopSessionToken))
    }

    @Test
    fun `reader rejects an oversized or linked session file`() {
        val oversizedPaths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-large-dev-session"))
        Files.writeString(oversizedPaths.agentDevelopmentSession, "x".repeat(8_193))

        assertFailsWith<IllegalArgumentException> {
            BusinessAgentDevelopmentSessionFile.read(oversizedPaths.agentDevelopmentSession)
        }

        val linkedPaths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-linked-dev-session"))
        val outside = Files.createTempFile("huitai-dev-session-outside", ".json")
        val linked = runCatching {
            Files.createSymbolicLink(linkedPaths.agentDevelopmentSession, outside)
        }.isSuccess
        if (linked) {
            assertFailsWith<IllegalArgumentException> {
                BusinessAgentDevelopmentSessionFile.read(linkedPaths.agentDevelopmentSession)
            }
        }
    }

    @Test
    fun `closing an old lease never deletes a replacement session`() {
        val paths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-replaced-dev-session"))
        val oldOwnership = BusinessAgentDevelopmentSessionFile.acquireOwnership(paths.agentDevelopmentSession)
        val oldLease = BusinessAgentDevelopmentSessionFile.publish(
            paths.agentDevelopmentSession,
            connectRequest(),
            oldOwnership,
        )
        oldOwnership.close()
        val replacementOwnership = BusinessAgentDevelopmentSessionFile.acquireOwnership(
            paths.agentDevelopmentSession,
        )
        val observed = requireNotNull(
            BusinessAgentDevelopmentSessionFile.observeIfExists(
                paths.agentDevelopmentSession,
                replacementOwnership,
            ),
        )
        BusinessAgentDevelopmentSessionFile.deleteIfUnchanged(observed, replacementOwnership)
        val replacementRequest = connectRequest()
        val replacementLease = BusinessAgentDevelopmentSessionFile.publish(
            paths.agentDevelopmentSession,
            replacementRequest,
            replacementOwnership,
        )

        assertFailsWith<IllegalStateException> { oldLease.close() }
        assertEquals(
            replacementRequest.identity.desktopSessionId,
            BusinessAgentDevelopmentSessionFile.read(paths.agentDevelopmentSession)
                .identity.desktopSessionId,
        )

        replacementLease.close()
        replacementOwnership.close()
    }

    private fun connectRequest(): AgentConnectRequest {
        val identity = DesktopSessionIdentity.forChildLaunch(
            desktopInstanceId = UUID.randomUUID().toString(),
            localOrigin = "http://127.0.0.1:49391",
        )
        return AgentConnectRequest(
            url = "ws://127.0.0.1:49391/ws/agent",
            identity = identity,
        )
    }
}
