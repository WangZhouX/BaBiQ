package com.wzx.huitai.desktop.runtime

import com.wzx.huitai.agent.client.AgentConnectRequest
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentTransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessAgentProcessLauncherTest {
    @Test
    fun `launcher clears inherited environment and copies only required operating system variables`() = runTest {
        val fixture = fixture(port = 43116)
        val process = FakeProcess()
        var capturedEnvironment = emptyMap<String, String>()
        val launcher = BusinessAgentProcessLauncher(
            processStarter = { builder ->
                capturedEnvironment = builder.environment().toMap()
                process
            },
            readinessProbe = BusinessAgentReadinessProbe(
                authenticator = AuthenticatedWebSocketProbe {
                    Files.deleteIfExists(fixture.paths.agentSessionToken)
                    true
                },
                retryDelayMillis = { },
            ),
            parentEnvironment = {
                mapOf(
                    "SystemRoot" to "C:\\Windows",
                    "TEMP" to "C:\\Temp",
                    "LANG" to "zh_CN.UTF-8",
                    "API_TOKEN" to "must-not-leak",
                    "DATABASE_PASSWORD" to "must-not-leak",
                    "HUITAI_DESKTOP_KEYSTORE_PASSWORD" to "must-not-leak",
                )
            },
        )

        launcher.launch(fixture.request).close()

        assertEquals("C:\\Windows", capturedEnvironment["SystemRoot"])
        assertEquals("C:\\Temp", capturedEnvironment["TEMP"])
        assertEquals("zh_CN.UTF-8", capturedEnvironment["LANG"])
        assertEquals(BACKEND_PASSWORD, capturedEnvironment[BusinessAgentLaunchRequest.BACKEND_KEYSTORE_PASSWORD_ENV])
        assertFalse("API_TOKEN" in capturedEnvironment)
        assertFalse("DATABASE_PASSWORD" in capturedEnvironment)
        assertFalse("HUITAI_DESKTOP_KEYSTORE_PASSWORD" in capturedEnvironment)
    }

    @Test
    fun `command uses exact business profile and isolated paths while password stays in environment`() {
        val fixture = fixture(port = 43117)
        val command = fixture.request.command()

        assertTrue(command.contains(fixture.javaExecutable.toString()))
        assertTrue(command.contains("-jar"))
        assertTrue(command.contains(fixture.backendJar.toString()))
        assertTrue(command.contains("--spring.profiles.active=business-desktop"))
        assertTrue(command.contains("--server.address=127.0.0.1"))
        assertTrue(command.contains("--server.port=43117"))
        assertTrue(command.contains("--babiq.business.runtime-dir=${fixture.paths.agentRoot}"))
        assertTrue(command.contains("--babiq.persistence.database-path=${fixture.paths.agentDatabase}"))
        assertTrue(command.contains("--babiq.secrets.keystore-path=${fixture.paths.agentKeyStore}"))
        assertTrue(command.contains("--logging.file.name=${fixture.paths.agentLog}"))
        assertTrue(command.contains("--babiq.memory.long-term.root-dir=${fixture.paths.agentMemoryRoot}"))
        assertTrue(command.contains("--babiq.team.root-dir=${fixture.paths.agentTeamRoot}"))
        assertTrue(command.contains("--babiq.business.backend-lock-path=${fixture.paths.agentInstanceLock}"))
        assertTrue(command.contains("--babiq.business.session-token-file=${fixture.paths.agentSessionToken}"))
        assertFalse(command.joinToString(" ").contains(BACKEND_PASSWORD))
        assertEquals(BACKEND_PASSWORD, fixture.request.environment()["BABIQ_SECRETS_KEYSTORE_PASSWORD"])
        assertFalse(fixture.request.toString().contains(BACKEND_PASSWORD))
        assertFalse(fixture.request.toString().contains(fixture.request.identity.desktopSessionToken))
        fixture.request.close()
    }

    @Test
    fun `request allocates one dynamic loopback port used by command and websocket url`() {
        val paths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-dynamic-port"))
        var allocations = 0
        val request = BusinessAgentLaunchRequest.create(
            paths = paths,
            desktopInstanceId = "installation-id",
            backendJar = paths.root.resolve("backend/babiq-server.jar"),
            javaExecutable = Path.of("java"),
            backendKeyStorePassword = BACKEND_PASSWORD.toCharArray(),
            loopbackPortAllocator = LoopbackPortAllocator {
                allocations += 1
                43991
            },
        )

        assertEquals(1, allocations)
        assertTrue(request.command().contains("--server.port=43991"))
        assertEquals("ws://127.0.0.1:43991/ws/agent", request.connectRequest.url)
        request.close()
    }

    @Test
    fun `launcher requires authenticated readiness and cleans child plus unconsumed token on failure`() = runTest {
        val fixture = fixture(port = 43118)
        val process = FakeProcess(alive = true, gracefulExit = true)
        val probe = BusinessAgentReadinessProbe(
            authenticator = AuthenticatedWebSocketProbe { false },
            timeoutMillis = 25,
            retryDelayMillis = { },
        )
        val launcher = BusinessAgentProcessLauncher(
            processStarter = { process },
            readinessProbe = probe,
        )

        assertFailsWith<IllegalStateException> { launcher.launch(fixture.request) }

        assertEquals(1, process.destroyCount)
        assertFalse(fixture.paths.agentSessionToken.toFile().exists())
        assertFalse(process.isAlive)
    }

    @Test
    fun `launcher detects log leaf replacement at process start and terminates the child`() = runTest {
        val fixture = fixture(port = 43125)
        val process = FakeProcess(alive = true, gracefulExit = true)
        val launcher = BusinessAgentProcessLauncher(
            processStarter = {
                Files.delete(fixture.paths.agentLog)
                Files.createDirectory(fixture.paths.agentLog)
                process
            },
            readinessProbe = BusinessAgentReadinessProbe(
                authenticator = AuthenticatedWebSocketProbe { true },
                retryDelayMillis = { },
            ),
        )

        assertFailsWith<IllegalArgumentException> { launcher.launch(fixture.request) }

        assertEquals(1, process.destroyCount)
        assertFalse(process.isAlive)
        assertFalse(Files.exists(fixture.paths.agentSessionToken))
    }

    @Test
    fun `readiness fails immediately when child exits before authenticated websocket`() = runTest {
        val fixture = fixture(port = 43122)
        var authenticationAttempts = 0
        val process = FakeProcess(alive = false)
        val launcher = BusinessAgentProcessLauncher(
            processStarter = { process },
            readinessProbe = BusinessAgentReadinessProbe(
                authenticator = AuthenticatedWebSocketProbe {
                    authenticationAttempts += 1
                    true
                },
                retryDelayMillis = { },
            ),
        )

        assertFailsWith<IllegalStateException> { launcher.launch(fixture.request) }
        assertEquals(0, authenticationAttempts)
        assertFalse(fixture.paths.agentSessionToken.toFile().exists())
    }

    @Test
    fun `authenticated handshake cannot win a race with child exit`() = runTest {
        val fixture = fixture(port = 43124)
        val process = FakeProcess(alive = true)
        val launcher = BusinessAgentProcessLauncher(
            processStarter = { process },
            readinessProbe = BusinessAgentReadinessProbe(
                authenticator = AuthenticatedWebSocketProbe {
                    Files.deleteIfExists(fixture.paths.agentSessionToken)
                    process.exitNow()
                    true
                },
                retryDelayMillis = { },
            ),
        )

        assertFailsWith<IllegalStateException> { launcher.launch(fixture.request) }
        assertFalse(process.isAlive)
    }

    @Test
    fun `successful launch keeps one child identity across reconnect and restart creates a fresh boundary`() = runTest {
        val paths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-launch"))
        val requests = mutableListOf<BusinessAgentLaunchRequest>()
        val processes = ArrayDeque(listOf(FakeProcess(), FakeProcess()))
        val launcher = BusinessAgentProcessLauncher(
            processStarter = { processes.removeFirst() },
            readinessProbe = BusinessAgentReadinessProbe(
                authenticator = AuthenticatedWebSocketProbe { request ->
                    Files.deleteIfExists(paths.agentSessionToken)
                    request.url.startsWith("ws://127.0.0.1:")
                },
                retryDelayMillis = { },
            ),
        )

        fun request(port: Int) = BusinessAgentLaunchRequest.create(
            paths = paths,
            desktopInstanceId = "installation-id",
            backendJar = paths.root.resolve("backend/babiq-server.jar"),
            javaExecutable = Path.of("java"),
            backendKeyStorePassword = BACKEND_PASSWORD.toCharArray(),
            port = port,
        ).also(requests::add)

        val first = launcher.launch(request(43119))
        assertSame(first.identity, first.identityForReconnect())
        assertSame(first.sequenceTracker, first.sequenceTrackerForReconnect())
        first.close()

        val second = launcher.launch(request(43120))
        assertNotEquals(first.identity.desktopSessionId, second.identity.desktopSessionId)
        assertNotEquals(first.identity.desktopSessionToken, second.identity.desktopSessionToken)
        assertTrue(first.sequenceTracker !== second.sequenceTracker)
        second.close()
    }

    @Test
    fun `close waits gracefully then forces an unresponsive child and is idempotent`() = runTest {
        val fixture = fixture(port = 43121)
        val process = FakeProcess(alive = true, gracefulExit = false)
        val launcher = BusinessAgentProcessLauncher(
            processStarter = { process },
            readinessProbe = BusinessAgentReadinessProbe(
                authenticator = AuthenticatedWebSocketProbe {
                    Files.deleteIfExists(fixture.paths.agentSessionToken)
                    true
                },
                retryDelayMillis = { },
            ),
        )
        val session = launcher.launch(fixture.request)

        session.close()
        session.close()

        assertEquals(1, process.destroyCount)
        assertEquals(1, process.destroyForciblyCount)
        assertTrue(process.waitTimeouts.contains(5L to TimeUnit.SECONDS))
        assertFalse(process.isAlive)
    }

    @Test
    fun `forced shutdown uses a second bounded wait and never waits forever`() {
        val process = FakeProcess(alive = true, gracefulExit = false, forcedExit = false)

        assertFailsWith<IllegalStateException> {
            BusinessAgentRuntimeSession.terminateProcess(process)
        }

        assertEquals(
            listOf(5L to TimeUnit.SECONDS, 5L to TimeUnit.SECONDS),
            process.waitTimeouts,
        )
        assertEquals(0, process.unboundedWaitCount)
    }

    @Test
    fun `launcher always closes launch request when child termination throws`() = runTest {
        val fixture = fixture(port = 43125)
        val process = FakeProcess(alive = true, destroyFailure = IllegalStateException("destroy failed"))
        val launcher = BusinessAgentProcessLauncher(
            processStarter = { process },
            readinessProbe = BusinessAgentReadinessProbe(
                authenticator = AuthenticatedWebSocketProbe { false },
                timeoutMillis = 1,
                retryDelayMillis = { },
            ),
        )

        assertFailsWith<IllegalStateException> { launcher.launch(fixture.request) }

        assertFalse(Files.exists(fixture.paths.agentSessionToken))
        assertFailsWith<IllegalStateException> { fixture.request.environment() }
    }

    @Test
    fun `readiness propagates cancellation instead of retrying it`() = runTest {
        val fixture = fixture(port = 43126)
        var attempts = 0
        val probe = BusinessAgentReadinessProbe(
            authenticator = AuthenticatedWebSocketProbe {
                attempts += 1
                throw CancellationException("cancelled")
            },
            retryDelayMillis = { },
        )

        assertFailsWith<CancellationException> {
            probe.await(FakeProcess(), fixture.request.connectRequest)
        }
        assertEquals(1, attempts)
        fixture.request.close()
    }

    @Test
    fun `supervisor facade changes connection id across reconnect while child session identity stays fixed`() = runTest {
        val fixture = fixture(port = 43123)
        val process = FakeProcess()
        val launcher = BusinessAgentProcessLauncher(
            processStarter = { process },
            readinessProbe = BusinessAgentReadinessProbe(
                authenticator = AuthenticatedWebSocketProbe {
                    Files.deleteIfExists(fixture.paths.agentSessionToken)
                    true
                },
                retryDelayMillis = { },
            ),
        )
        val session = launcher.launch(fixture.request)
        val desktopSessionId = session.identity.desktopSessionId
        val first = FakeAgentConnection("connection-1")
        val second = FakeAgentConnection("connection-2")
        val facade = session.connect(QueueAgentTransport(first, second), this)

        assertEquals("connection-1", facade.connectionId)
        first.mutableState.value = AgentConnectionState.TransportFailure()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(AgentConnectionState.Connected, facade.state.value)
        assertEquals("connection-2", facade.connectionId)
        assertEquals(desktopSessionId, session.identity.desktopSessionId)
        facade.close()
        session.close()
    }

    private fun fixture(port: Int): Fixture {
        val paths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-launch-request"))
        val javaExecutable = Path.of("C:/runtime/bin/java.exe")
        val backendJar = paths.root.resolve("backend/babiq-server.jar")
        val request = BusinessAgentLaunchRequest.create(
            paths = paths,
            desktopInstanceId = "installation-id",
            backendJar = backendJar,
            javaExecutable = javaExecutable,
            backendKeyStorePassword = BACKEND_PASSWORD.toCharArray(),
            port = port,
        )
        return Fixture(paths, javaExecutable, backendJar, request)
    }

    private data class Fixture(
        val paths: BusinessDesktopRuntimePaths,
        val javaExecutable: Path,
        val backendJar: Path,
        val request: BusinessAgentLaunchRequest,
    )

    private class FakeProcess(
        alive: Boolean = true,
        private val gracefulExit: Boolean = true,
        private val forcedExit: Boolean = true,
        private val destroyFailure: RuntimeException? = null,
    ) : Process() {
        private var aliveState = alive
        var destroyCount = 0
            private set
        var destroyForciblyCount = 0
            private set
        var unboundedWaitCount = 0
            private set
        val waitTimeouts = mutableListOf<Pair<Long, TimeUnit>>()

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { unboundedWaitCount += 1; aliveState = false; return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitTimeouts += timeout to unit
            if (gracefulExit) aliveState = false
            return !aliveState
        }
        override fun exitValue(): Int = if (aliveState) throw IllegalThreadStateException() else 0
        override fun destroy() {
            destroyCount += 1
            destroyFailure?.let { throw it }
            if (gracefulExit) aliveState = false
        }
        override fun destroyForcibly(): Process {
            destroyForciblyCount += 1
            if (forcedExit) aliveState = false
            return this
        }
        override fun isAlive(): Boolean = aliveState
        fun exitNow() { aliveState = false }
    }

    private class QueueAgentTransport(vararg connections: FakeAgentConnection) : AgentTransport {
        private val remaining = ArrayDeque(connections.toList())
        override suspend fun connect(request: AgentConnectRequest): AgentConnection = remaining.removeFirst()
        override suspend fun close() = Unit
    }

    private class FakeAgentConnection(
        override val connectionId: String,
    ) : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        val mutableState = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val incoming: ReceiveChannel<String> = incomingChannel
        override val state: StateFlow<AgentConnectionState> = mutableState
        override var hasConnected: Boolean = true
        override suspend fun send(text: String) = Unit
        override suspend fun close() { incomingChannel.close() }
    }

    private companion object {
        const val BACKEND_PASSWORD = "backend-password-secret"
    }
}
