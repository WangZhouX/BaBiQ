package com.wzx.huitai.desktop.agent

import com.wzx.huitai.agent.application.ApplicationIdentityPublisherClient
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.IdentityEnvelope
import com.wzx.huitai.integration.auth.AuthIdentitySnapshot
import com.wzx.huitai.integration.identity.AuthIdentityBinding
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ApplicationIdentityBindingAdapterTest {
    @Test
    fun `bind and update forward complete mapped identity with deterministic sequence and time`() = runTest {
        val delegate = RecordingIdentityClient()
        val times = ArrayDeque(
            listOf(
                Instant.parse("2026-07-16T10:00:00Z"),
                Instant.parse("2026-07-16T10:00:01Z"),
            ),
        )
        val adapter = adapter(delegate, now = { times.removeFirst() })

        adapter.bind(AuthIdentityBinding(8, identity(identityEpoch = 8)))
        adapter.update(AuthIdentityBinding(9, identity(identityEpoch = 9, tenantId = "tenant-2")))

        val bind = delegate.binds.single()
        assertCommon(bind, identityEpoch = 8, sequence = 1, generatedAt = "2026-07-16T10:00:00Z")
        assertTrue(bind.authenticated)
        assertEquals("auth-session-1", bind.common.authSessionId)
        assertEquals("user-1", bind.common.userId)
        assertEquals("tenant-1", bind.common.tenantId)
        assertEquals("platform-1", bind.common.platformId)
        assertEquals(setOf("lawyer", "partner"), bind.roles)
        assertEquals(setOf("case:read", "case:write"), bind.permissions)

        val update = delegate.updates.single()
        assertCommon(update, identityEpoch = 9, sequence = 2, generatedAt = "2026-07-16T10:00:01Z")
        assertEquals("tenant-2", update.common.tenantId)
    }

    @Test
    fun `signed out update clears business identity and never calls bind`() = runTest {
        val delegate = RecordingIdentityClient()
        val adapter = adapter(delegate)

        adapter.update(AuthIdentityBinding(identityEpoch = 10, identity = null))

        assertTrue(delegate.binds.isEmpty())
        val signedOut = delegate.updates.single()
        assertFalse(signedOut.authenticated)
        assertNull(signedOut.common.authSessionId)
        assertNull(signedOut.common.userId)
        assertNull(signedOut.common.tenantId)
        assertNull(signedOut.common.platformId)
        assertTrue(signedOut.roles.isEmpty())
        assertTrue(signedOut.permissions.isEmpty())
        assertEquals(10, signedOut.common.identityEpoch)
    }

    @Test
    fun `delegate failures propagate without translation`() = runTest {
        val expected = IllegalStateException("delegate failure")
        val delegate = RecordingIdentityClient().apply { failure = expected }
        val adapter = adapter(delegate)

        val actual = assertFailsWith<IllegalStateException> {
            adapter.bind(AuthIdentityBinding(8, identity(identityEpoch = 8)))
        }

        assertTrue(actual === expected)
    }

    @Test
    fun `sibling core production sources never import each other`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val agentSources = root.resolve("agent-client-core/src/main")
        val integrationSources = root.resolve("huitai-integration-core/src/main")

        assertFalse(sourceContains(agentSources, "com.wzx.huitai.integration"))
        assertFalse(sourceContains(integrationSources, "com.wzx.huitai.agent"))
    }

    private fun adapter(
        delegate: ApplicationIdentityPublisherClient,
        now: () -> Instant = { Instant.parse("2026-07-16T10:00:00Z") },
    ) = ApplicationIdentityBindingAdapter(
        delegate = delegate,
        desktopSessionIdentity = DesktopSessionIdentity(
            desktopInstanceId = "desktop-1",
            desktopSessionId = "desktop-session-1",
            desktopSessionToken = "secret-token",
            localOrigin = "http://127.0.0.1",
        ),
        nextSequence = sequenceSupplier(),
        now = now,
    )

    private fun assertCommon(envelope: IdentityEnvelope, identityEpoch: Long, sequence: Long, generatedAt: String) {
        assertEquals(ApplicationProtocol.PROTOCOL_VERSION, envelope.common.protocolVersion)
        assertEquals("desktop-1", envelope.common.desktopInstanceId)
        assertEquals("desktop-session-1", envelope.common.desktopSessionId)
        assertEquals(identityEpoch, envelope.common.identityEpoch)
        assertEquals(sequence, envelope.common.sequence)
        assertEquals(generatedAt, envelope.common.generatedAt)
    }

    private fun identity(identityEpoch: Long, tenantId: String = "tenant-1") = AuthIdentitySnapshot(
        authSessionId = "auth-session-1",
        identityEpoch = identityEpoch,
        userId = "user-1",
        tenantId = tenantId,
        platformId = "platform-1",
        roles = linkedSetOf("lawyer", "partner"),
        permissions = linkedSetOf("case:read", "case:write"),
        authenticatedAt = Instant.parse("2026-07-16T09:00:00Z"),
    )

    private fun sequenceSupplier(): () -> Long {
        var sequence = 0L
        return { ++sequence }
    }

    private fun sourceContains(root: Path, text: String): Boolean = Files.walk(root).use { paths ->
        paths.filter { it.isRegularFile() && it.extension in setOf("kt", "kts") }
            .anyMatch { it.readText().contains(text) }
    }

    private class RecordingIdentityClient : ApplicationIdentityPublisherClient {
        val binds = mutableListOf<IdentityEnvelope>()
        val updates = mutableListOf<IdentityEnvelope>()
        var failure: RuntimeException? = null

        override suspend fun bind(envelope: IdentityEnvelope) {
            failure?.let { throw it }
            binds += envelope
        }

        override suspend fun update(envelope: IdentityEnvelope) {
            failure?.let { throw it }
            updates += envelope
        }
    }
}
