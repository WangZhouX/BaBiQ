package com.wzx.huitai.desktop.runtime

import com.wzx.huitai.agent.client.AgentConnectRequest
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import com.wzx.huitai.security.secret.JceksSecretStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BusinessBackendDevelopmentRunnerTest {
    @Test
    fun `standalone backend reuses desktop vault secret and publishes frontend session`() = runTest {
        val home = Files.createTempDirectory("huitai-standalone-backend")
        val paths = BusinessDesktopRuntimePaths.create(home)
        val backendJar = paths.root.resolve("backend/babiq-server.jar")
        Files.createDirectories(backendJar.parent)
        Files.writeString(backendJar, "test")
        val desktopPassword = "desktop-master-password".toCharArray()
        val backendPassword = "existing-backend-password".toCharArray()
        JceksSecretStore(paths.desktopKeyStore, desktopPassword).use { store ->
            store.upsert(BusinessBackendKeyStorePasswordVault.ALIAS, backendPassword)
        }
        val process = FakeProcess()
        var launchedPassword: String? = null
        val runner = BusinessBackendDevelopmentRunner(
            home = home,
            backendJar = backendJar,
            port = 49391,
            desktopSecretBootstrap = { desktopPassword.copyOf() },
            sessionLauncher = { request ->
                launchedPassword =
                    request.environment()[BusinessAgentLaunchRequest.BACKEND_KEYSTORE_PASSWORD_ENV]
                Files.deleteIfExists(paths.agentSessionToken)
                BusinessAgentRuntimeSession(process, request)
            },
        )

        val handle = runner.start()
        val published = BusinessAgentDevelopmentSessionFile.read(paths.agentDevelopmentSession)

        assertEquals("existing-backend-password", launchedPassword)
        assertEquals("ws://127.0.0.1:49391/ws/agent", published.url)
        assertTrue(process.isAlive)

        handle.close()

        assertFalse(Files.exists(paths.agentDevelopmentSession))
        assertFalse(process.isAlive)
    }

    @Test
    fun `stale development session is reclaimed before standalone backend starts`() = runTest {
        val fixture = fixture("huitai-stale-backend")
        val staleRequest = connectRequest()
        publishStale(fixture.paths.agentDevelopmentSession, staleRequest)
        val process = FakeProcess()
        var launches = 0
        val runner = fixture.runner(
            process = process,
            existingSessionProbe = AuthenticatedWebSocketProbe { false },
            portAvailabilityProbe = BusinessBackendPortAvailabilityProbe { true },
            onLaunch = { launches++ },
        )

        val handle = runner.start()
        val replacement = BusinessAgentDevelopmentSessionFile.read(fixture.paths.agentDevelopmentSession)

        assertEquals(1, launches)
        assertNotEquals(staleRequest.identity.desktopSessionId, replacement.identity.desktopSessionId)
        assertTrue(process.isAlive)

        handle.close()
    }

    @Test
    fun `authenticated development session prevents duplicate backend startup`() = runTest {
        val fixture = fixture("huitai-live-backend")
        publishStale(fixture.paths.agentDevelopmentSession, connectRequest())
        var launches = 0
        var portProbes = 0
        val runner = fixture.runner(
            existingSessionProbe = AuthenticatedWebSocketProbe { true },
            portAvailabilityProbe = BusinessBackendPortAvailabilityProbe {
                portProbes++
                true
            },
            onLaunch = { launches++ },
        )

        val failure = assertFailsWith<IllegalStateException> { runner.start() }

        assertTrue(failure.message.orEmpty().contains("already running"))
        assertEquals(0, launches)
        assertEquals(0, portProbes)
        assertTrue(Files.exists(fixture.paths.agentDevelopmentSession))
        Files.delete(fixture.paths.agentDevelopmentSession)
    }

    @Test
    fun `concurrent development owner prevents recovery and child launch`() = runTest {
        val fixture = fixture("huitai-owned-backend")
        val ownership = BusinessAgentDevelopmentSessionFile.acquireOwnership(
            fixture.paths.agentDevelopmentSession,
        )
        val lease = BusinessAgentDevelopmentSessionFile.publish(
            fixture.paths.agentDevelopmentSession,
            connectRequest(),
            ownership,
        )
        var launches = 0
        var authenticationAttempts = 0
        val runner = fixture.runner(
            existingSessionProbe = AuthenticatedWebSocketProbe {
                authenticationAttempts++
                false
            },
            portAvailabilityProbe = BusinessBackendPortAvailabilityProbe { true },
            onLaunch = { launches++ },
        )

        val failure = assertFailsWith<IllegalStateException> { runner.start() }

        assertTrue(failure.message.orEmpty().contains("already running"))
        assertEquals(0, launches)
        assertEquals(0, authenticationAttempts)
        assertTrue(Files.exists(fixture.paths.agentDevelopmentSession))
        lease.close()
        ownership.close()
    }

    @Test
    fun `unreachable session on occupied port is preserved`() = runTest {
        val fixture = fixture("huitai-occupied-backend")
        publishStale(fixture.paths.agentDevelopmentSession, connectRequest())
        var launches = 0
        val runner = fixture.runner(
            existingSessionProbe = AuthenticatedWebSocketProbe { false },
            portAvailabilityProbe = BusinessBackendPortAvailabilityProbe { false },
            onLaunch = { launches++ },
        )

        val failure = assertFailsWith<IllegalStateException> { runner.start() }

        assertTrue(failure.message.orEmpty().contains("port 49391 is already in use"))
        assertEquals(0, launches)
        assertTrue(Files.exists(fixture.paths.agentDevelopmentSession))
        Files.delete(fixture.paths.agentDevelopmentSession)
    }

    @Test
    fun `invalid stale session is reclaimed only when its port is free`() = runTest {
        val fixture = fixture("huitai-invalid-backend")
        Files.writeString(fixture.paths.agentDevelopmentSession, "not-json")
        val process = FakeProcess()
        var authenticationAttempts = 0
        val runner = fixture.runner(
            process = process,
            existingSessionProbe = AuthenticatedWebSocketProbe {
                authenticationAttempts++
                true
            },
            portAvailabilityProbe = BusinessBackendPortAvailabilityProbe { true },
        )

        val handle = runner.start()

        assertEquals(0, authenticationAttempts)
        assertTrue(Files.isRegularFile(fixture.paths.agentDevelopmentSession))
        assertTrue(process.isAlive)
        handle.close()
    }

    @Test
    fun `session replaced during liveness probe is not deleted`() = runTest {
        val fixture = fixture("huitai-replaced-backend")
        publishStale(fixture.paths.agentDevelopmentSession, connectRequest())
        val replacementRequest = connectRequest()
        val replacementBytes = serializedSession(replacementRequest)
        var launches = 0
        val runner = fixture.runner(
            existingSessionProbe = AuthenticatedWebSocketProbe {
                Files.delete(fixture.paths.agentDevelopmentSession)
                Files.write(fixture.paths.agentDevelopmentSession, replacementBytes)
                false
            },
            portAvailabilityProbe = BusinessBackendPortAvailabilityProbe { true },
            onLaunch = { launches++ },
        )

        assertFailsWith<IllegalArgumentException> { runner.start() }

        assertEquals(0, launches)
        assertEquals(
            replacementRequest.identity.desktopSessionId,
            BusinessAgentDevelopmentSessionFile.read(fixture.paths.agentDevelopmentSession)
                .identity.desktopSessionId,
        )
        replacementBytes.fill(0)
        Files.delete(fixture.paths.agentDevelopmentSession)
    }

    @Test
    fun `default loopback probe distinguishes occupied and released ports`() {
        val probe = loopbackPortAvailabilityProbe()
        val loopback = InetAddress.getByName("127.0.0.1")
        val port = ServerSocket(0, 1, loopback).use { socket ->
            assertFalse(probe.isAvailable(socket.localPort))
            socket.localPort
        }

        assertTrue(probe.isAvailable(port))
    }

    private fun fixture(prefix: String): RunnerFixture {
        val home = Files.createTempDirectory(prefix)
        val paths = BusinessDesktopRuntimePaths.create(home)
        val backendJar = paths.root.resolve("backend/babiq-server.jar")
        Files.createDirectories(backendJar.parent)
        Files.writeString(backendJar, "test")
        val desktopPassword = "desktop-master-password".toCharArray()
        JceksSecretStore(paths.desktopKeyStore, desktopPassword).use { store ->
            store.upsert(BusinessBackendKeyStorePasswordVault.ALIAS, "backend-password".toCharArray())
        }
        return RunnerFixture(home, paths, backendJar, desktopPassword)
    }

    private fun connectRequest(): AgentConnectRequest = AgentConnectRequest(
        url = "ws://127.0.0.1:49391/ws/agent",
        identity = DesktopSessionIdentity.forChildLaunch(
            desktopInstanceId = UUID.randomUUID().toString(),
            localOrigin = "http://127.0.0.1",
        ),
    )

    private fun publishStale(path: Path, request: AgentConnectRequest) {
        val ownership = BusinessAgentDevelopmentSessionFile.acquireOwnership(path)
        BusinessAgentDevelopmentSessionFile.publish(path, request, ownership)
        ownership.close()
    }

    private fun serializedSession(request: AgentConnectRequest): ByteArray {
        val root = Files.createTempDirectory("huitai-serialized-session")
        val path = root.resolve("development-session.json")
        val ownership = BusinessAgentDevelopmentSessionFile.acquireOwnership(path)
        val lease = BusinessAgentDevelopmentSessionFile.publish(path, request, ownership)
        val bytes = Files.readAllBytes(path)
        lease.close()
        ownership.close()
        return bytes
    }

    private data class RunnerFixture(
        val home: Path,
        val paths: BusinessDesktopRuntimePaths,
        val backendJar: Path,
        val desktopPassword: CharArray,
    ) {
        fun runner(
            process: FakeProcess = FakeProcess(),
            existingSessionProbe: AuthenticatedWebSocketProbe,
            portAvailabilityProbe: BusinessBackendPortAvailabilityProbe,
            onLaunch: () -> Unit = {},
        ): BusinessBackendDevelopmentRunner = BusinessBackendDevelopmentRunner(
            home = home,
            backendJar = backendJar,
            port = 49391,
            desktopSecretBootstrap = { desktopPassword.copyOf() },
            sessionLauncher = { request ->
                onLaunch()
                Files.deleteIfExists(paths.agentSessionToken)
                BusinessAgentRuntimeSession(process, request)
            },
            existingSessionProbe = existingSessionProbe,
            portAvailabilityProbe = portAvailabilityProbe,
        )
    }

    private class FakeProcess : Process() {
        private var alive = true
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int {
            alive = false
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() {
            alive = false
        }
        override fun destroyForcibly(): Process {
            alive = false
            return this
        }
        override fun isAlive(): Boolean = alive
    }
}
