package com.wzx.huitai.desktop.integration

import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcException
import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.agent.client.KtorAgentTransport
import com.wzx.huitai.agent.conversation.BusinessAgentClient
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderDraft
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.desktop.app.BusinessAgentChildHandle
import com.wzx.huitai.desktop.app.BusinessAgentChildLauncher
import com.wzx.huitai.desktop.app.BusinessAgentConnectionHandle
import com.wzx.huitai.desktop.app.BusinessAgentConnector
import com.wzx.huitai.desktop.app.BusinessDesktopCompositionRoot
import com.wzx.huitai.desktop.app.BusinessDesktopProductionConfiguration
import com.wzx.huitai.desktop.app.CompositionResource
import com.wzx.huitai.desktop.app.DesktopSecretBootstrap
import com.wzx.huitai.desktop.app.ProductionBusinessDesktopCompositionFactory
import com.wzx.huitai.desktop.logging.DesktopLoggingBootstrap
import com.wzx.huitai.desktop.runtime.AuthenticatedWebSocketProbe
import com.wzx.huitai.desktop.runtime.BusinessAgentLaunchRequest
import com.wzx.huitai.desktop.runtime.BusinessAgentProcessLauncher
import com.wzx.huitai.desktop.runtime.BusinessAgentReadinessProbe
import com.wzx.huitai.desktop.runtime.BusinessAgentRuntimeSession
import com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths
import com.wzx.huitai.desktop.runtime.ManagedBusinessAgentConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BusinessProviderSettingsRestartIT {
    @Test
    fun `Provider settings persist across a real backend restart`() = runBlocking {
        DesktopLoggingBootstrap.resetForTests()
        val backendJar = Path.of(requireNotNull(System.getProperty("huitai.backend.jar")))
            .toAbsolutePath()
            .normalize()
        assertTrue(Files.isRegularFile(backendJar), "backend jar must be built before restart IT")

        val home = Files.createTempDirectory("business-provider-restart-it")
        val workspace = Files.createDirectories(home.resolve("workspace"))
        val suffix = UUID.randomUUID().toString().take(8)
        val relayId = "restart-relay-$suffix"
        val secondaryId = "restart-secondary-$suffix"
        val createSecret = "sk-restart-create-$suffix"
        val updateSecret = "sk-restart-update-$suffix"
        val traffic = JsonRpcTrafficAudit(createSecret, updateSecret)
        val diagnostics = SecretDiagnosticAudit(createSecret, updateSecret)
        val fakeOa = BusinessRealBackendTestHarness.FakeOaServer.start()
        var first: RunningDesktop? = null
        var second: RunningDesktop? = null
        var primaryFailure: Throwable? = null

        try {
            withTimeout(OVERALL_TIMEOUT_MILLIS) {
                first = startDesktop(home, backendJar, traffic, fakeOa)
                val firstRunning = requireNotNull(first)
                assertTrue(Files.isDirectory(workspace))
                val firstClient = firstRunning.client
                val initialProviders = firstClient.listProviders()
                assertTrue(initialProviders.isNotEmpty())

                val createDraft = BusinessProviderDraft(
                    providerId = relayId,
                    displayName = "Restart Relay $suffix",
                    type = "OPENAI_COMPATIBLE",
                    authMode = "api_key",
                    baseUrl = "https://relay-initial.example.invalid/v1",
                    model = "kimi-k3",
                    apiKey = createSecret,
                    contextWindow = 131_072,
                )
                diagnostics.inspect(createDraft)
                val created = firstClient.createProvider(createDraft)
                assertProvider(created, createDraft, hasApiKey = true)
                val firstSelection = firstClient.setActiveProvider(relayId)
                assertEquals(relayId, firstSelection.providerId)
                assertEquals(createDraft.model, firstSelection.modelId)

                val updateDraft = createDraft.copy(
                    displayName = "Restart Relay Updated $suffix",
                    baseUrl = "https://relay-updated.example.invalid/v1",
                    model = "kimi-k3-restart",
                    apiKey = updateSecret,
                    contextWindow = 262_144,
                )
                diagnostics.inspect(updateDraft)
                val updated = firstClient.updateProvider(updateDraft)
                assertProvider(updated, updateDraft, hasApiKey = true)

                val secondaryDraft = BusinessProviderDraft(
                    providerId = secondaryId,
                    displayName = "Restart Secondary $suffix",
                    type = "OPENAI_COMPATIBLE",
                    authMode = "api_key",
                    baseUrl = "https://relay-secondary.example.invalid/v1",
                    model = "secondary-model",
                    apiKey = createSecret,
                    contextWindow = 65_536,
                )
                diagnostics.inspect(secondaryDraft)
                val secondary = firstClient.createProvider(secondaryDraft)
                assertProvider(secondary, secondaryDraft, hasApiKey = true)
                val secondarySelection = firstClient.setActiveProvider(secondaryId)
                assertEquals(secondaryId, secondarySelection.providerId)

                val beforeDelete = firstClient.listProviders()
                val expectedFallback = beforeDelete.asSequence()
                    .filter(BusinessProvider::enabled)
                    .map(BusinessProvider::id)
                    .filterNot(secondaryId::equals)
                    .sorted()
                    .first()
                val deleteResult = firstClient.deleteProvider(secondaryId)
                assertTrue(deleteResult.ok)
                assertEquals(secondaryId, deleteResult.providerId)
                assertEquals(expectedFallback, deleteResult.activeProviderId)
                diagnostics.inspect(
                    listOf(initialProviders, created, firstSelection, updated, secondary, secondarySelection, deleteResult),
                )

                firstRunning.shutdown()
                firstRunning.assertFullyStopped()

                second = startDesktop(home, backendJar, traffic, fakeOa)
                val secondRunning = requireNotNull(second)
                assertNotEquals(firstRunning.pid, secondRunning.pid)
                assertEquals(firstRunning.paths.root, secondRunning.paths.root)
                assertEquals(firstRunning.paths.agentDatabase, secondRunning.paths.agentDatabase)
                assertEquals(firstRunning.paths.agentKeyStore, secondRunning.paths.agentKeyStore)

                val restartedProviders = secondRunning.client.listProviders()
                val restartedRelay = restartedProviders.single { it.id == relayId }
                assertProvider(restartedRelay, updateDraft.copy(apiKey = null), hasApiKey = true)
                assertFalse(restartedProviders.any { it.id == secondaryId })
                assertEquals(expectedFallback, restartedProviders.single(BusinessProvider::active).id)

                val testResult = secondRunning.client.testProvider(relayId)
                assertTrue(testResult.ok)
                assertEquals(relayId, testResult.providerId)
                assertEquals("Provider 配置可用", testResult.message)

                val duplicateDraft = updateDraft.copy(apiKey = createSecret)
                diagnostics.inspect(duplicateDraft)
                val duplicateFailure = assertFailsWith<AgentJsonRpcException> {
                    secondRunning.client.createProvider(duplicateDraft)
                }
                assertThrowableSecretFree(duplicateFailure, createSecret, updateSecret)
                diagnostics.inspect(listOf(restartedProviders, restartedRelay, testResult, duplicateFailure))

                secondRunning.shutdown()
                secondRunning.assertFullyStopped()

                assertSoftDeleted(firstRunning.paths.agentDatabase, secondaryId)
                assertSecretAbsent(firstRunning.paths.agentDatabase, createSecret, updateSecret)
                assertSecretAbsent(firstRunning.paths.agentKeyStore, createSecret, updateSecret)
                assertSecretAbsent(firstRunning.paths.agentLog, createSecret, updateSecret)
                assertSecretAbsent(firstRunning.paths.desktopLog, createSecret, updateSecret)
                BusinessRealBackendTestHarness.assertOaSecretsAbsent(
                    firstRunning.paths.root,
                    firstRunning.paths.agentDatabase,
                    firstRunning.paths.agentKeyStore,
                    firstRunning.paths.desktopDatabase,
                    firstRunning.paths.desktopKeyStore,
                    firstRunning.paths.agentLog,
                    firstRunning.paths.desktopLog,
                )
            }
        } catch (failure: Throwable) {
            diagnostics.inspect(failure.stackTraceToString())
            val reportedFailure = if (diagnostics.isSecure()) {
                failure
            } else {
                AssertionError("sensitive marker leaked into failure diagnostics")
            }
            primaryFailure = reportedFailure
            throw reportedFailure
        } finally {
            val cleanupFailure = withContext(NonCancellable) {
                cleanupAndAssert(first, second)
            }
            fakeOa.close()
            if (cleanupFailure != null) {
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }

        traffic.assertSecure()
        diagnostics.assertSecure()
    }

    private suspend fun startDesktop(
        home: Path,
        backendJar: Path,
        traffic: JsonRpcTrafficAudit,
        fakeOa: BusinessRealBackendTestHarness.FakeOaServer,
    ): RunningDesktop {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val capturedSession = AtomicReference<BusinessAgentRuntimeSession>()
        val capturedPaths = AtomicReference<BusinessDesktopRuntimePaths>()
        val childLauncher = BusinessAgentChildLauncher { context ->
            capturedPaths.set(context.paths)
            val request = BusinessAgentLaunchRequest.create(
                paths = context.paths,
                desktopInstanceId = context.desktopInstanceId,
                backendJar = context.backendJar,
                backendKeyStorePassword = context.backendKeyStorePassword,
            )
            val session = BusinessAgentProcessLauncher(
                readinessProbe = authenticatedReadinessProbe(),
                parentEnvironment = {
                    BusinessRealBackendTestHarness.safeParentEnvironment(fakeOa.baseUrl)
                },
            ).launch(request)
            capturedSession.set(session)
            BusinessAgentChildHandle(
                identity = session.identity,
                sequenceTracker = session.sequenceTracker,
                resource = CompositionResource { session.close() },
                runtimeSession = session,
            )
        }
        val connector = BusinessAgentConnector {
            val session = requireNotNull(capturedSession.get())
            val client = HttpClient(CIO) { install(WebSockets) }
            val transport = KtorAgentTransport(client, scope)
            try {
                val managed = session.connect(transport, scope) as ManagedBusinessAgentConnection
                val recording = RecordingManagedConnection(managed, scope, traffic)
                BusinessAgentConnectionHandle(
                    connection = recording,
                    resource = CompositionResource {
                        closeConnectionResources(recording, transport, client)?.let { throw it }
                    },
                )
            } catch (failure: Throwable) {
                val cleanupFailure = withContext(NonCancellable) {
                    closeConnectionResources(null, transport, client)
                }
                cleanupFailure?.let(failure::addSuppressed)
                throw failure
            }
        }
        val factory = ProductionBusinessDesktopCompositionFactory(
            configuration = BusinessDesktopProductionConfiguration(
                home = home,
                backendJar = backendJar,
                desktopSecretBootstrap = DesktopSecretBootstrap { DESKTOP_KEYSTORE_PASSWORD.toCharArray() },
            ),
            parentScope = scope,
            childLauncher = childLauncher,
            connector = connector,
        )

        return try {
            val root = withTimeout(STARTUP_TIMEOUT_MILLIS) { BusinessDesktopCompositionRoot.start(factory) }
            BusinessRealBackendTestHarness.loginReady(root)
            val view = assertNotNull(root.runtimeView)
            RunningDesktop(
                root = root,
                client = view.production.businessAgentClient,
                session = requireNotNull(capturedSession.get()),
                paths = requireNotNull(capturedPaths.get()),
                scope = scope,
            )
        } catch (failure: Throwable) {
            val cleanupFailure = withContext(NonCancellable) {
                var cleanup: Throwable? = null
                try {
                    capturedSession.get()?.let { closeSessionBounded(it) }
                } catch (closeFailure: Throwable) {
                    cleanup = closeFailure
                } finally {
                    scope.cancel()
                }
                cleanup
            }
            cleanupFailure?.let(failure::addSuppressed)
            throw failure
        }
    }

    private suspend fun closeConnectionResources(
        connection: RecordingManagedConnection?,
        transport: KtorAgentTransport,
        client: HttpClient,
    ): Throwable? = withContext(NonCancellable) {
        var cleanupFailure: Throwable? = null
        suspend fun capture(block: suspend () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
            }
        }
        capture { connection?.close() }
        capture { transport.close() }
        capture { client.close() }
        cleanupFailure
    }

    private suspend fun closeSessionBounded(session: BusinessAgentRuntimeSession) {
        runIndependentBounded(SESSION_CLOSE_TIMEOUT_MILLIS) { session.close() }
    }

    private suspend fun shutdownRootBounded(root: BusinessDesktopCompositionRoot) {
        runIndependentBounded(ROOT_SHUTDOWN_TIMEOUT_MILLIS) { root.shutdown() }
    }

    private suspend fun <T> runIndependentBounded(
        timeoutMillis: Long,
        block: suspend () -> T,
    ): T {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val work = owner.async { block() }
        return try {
            withTimeout(timeoutMillis) { work.await() }
        } finally {
            owner.cancel()
        }
    }

    private fun authenticatedReadinessProbe() = BusinessAgentReadinessProbe(
        authenticator = AuthenticatedWebSocketProbe { request ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = HttpClient(CIO) { install(WebSockets) }
            val transport = KtorAgentTransport(client, scope)
            var connection: AgentConnection? = null
            try {
                connection = transport.connect(request)
                withTimeout(2_000) {
                    connection.state.first { state -> state != AgentConnectionState.Connecting }
                } == AgentConnectionState.Connected
            } catch (_: Exception) {
                false
            } finally {
                withContext(NonCancellable) {
                    runCatching { connection?.close() }
                    runCatching { transport.close() }
                    scope.cancel()
                    client.close()
                }
            }
        },
        timeoutMillis = STARTUP_TIMEOUT_MILLIS,
    )

    private fun assertProvider(
        actual: BusinessProvider,
        expected: BusinessProviderDraft,
        hasApiKey: Boolean,
    ) {
        assertEquals(expected.providerId, actual.id)
        assertEquals(expected.displayName, actual.displayName)
        assertEquals(expected.type, actual.type)
        assertEquals(expected.authMode, actual.authMode)
        assertEquals(expected.baseUrl, actual.baseUrl)
        assertEquals(expected.model, actual.model)
        assertEquals(expected.contextWindow, actual.contextWindow)
        assertEquals(expected.enabled, actual.enabled)
        assertEquals(hasApiKey, actual.hasApiKey)
    }

    private fun assertSoftDeleted(database: Path, providerId: String) {
        Class.forName("org.sqlite.JDBC")
        val normalized = database.toAbsolutePath().normalize().toString().replace('\\', '/')
        DriverManager.getConnection("jdbc:sqlite:file:$normalized?mode=ro").use { connection ->
            connection.prepareStatement(
                "SELECT enabled FROM bq_provider_configs WHERE provider_id = ?",
            ).use { statement ->
                statement.setString(1, providerId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
            }
        }
    }

    private fun assertSecretAbsent(path: Path, vararg secrets: String) {
        assertTrue(Files.isRegularFile(path))
        val content = Files.readAllBytes(path)
        secrets.forEach { secret -> assertFalse(content.containsSubsequence(secret.toByteArray())) }
    }

    private fun assertThrowableSecretFree(failure: Throwable, vararg secrets: String) {
        generateSequence(failure) { it.cause }.forEach { current ->
            val diagnostic = current.toString()
            secrets.forEach { secret -> assertFalse(diagnostic.contains(secret)) }
        }
    }

    private suspend fun cleanupAndAssert(
        first: RunningDesktop?,
        second: RunningDesktop?,
    ): Throwable? {
        var cleanupFailure: Throwable? = null
        suspend fun capture(block: suspend () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
            }
        }
        capture { second?.shutdown() }
        capture { first?.shutdown() }
        capture { second?.assertFullyStopped() }
        capture { first?.assertFullyStopped() }
        capture { DesktopLoggingBootstrap.resetForTests() }
        return cleanupFailure
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { offset ->
            candidate.indices.all { index -> this[offset + index] == candidate[index] }
        }
    }

    private inner class RunningDesktop(
        private val root: BusinessDesktopCompositionRoot,
        val client: BusinessAgentClient,
        val session: BusinessAgentRuntimeSession,
        val paths: BusinessDesktopRuntimePaths,
        private val scope: CoroutineScope,
    ) {
        private val shutdownComplete = AtomicBoolean(false)
        val pid: Long = session.childPid

        suspend fun shutdown() {
            if (shutdownComplete.get()) return
            var shutdownFailure: Throwable? = null
            withContext(NonCancellable) {
                try {
                    shutdownRootBounded(root)
                } catch (failure: Throwable) {
                    shutdownFailure = failure
                    try {
                        closeSessionBounded(session)
                    } catch (fallbackFailure: Throwable) {
                        failure.addSuppressed(fallbackFailure)
                    }
                } finally {
                    scope.cancel()
                }
            }
            shutdownFailure?.let { throw it }
            shutdownComplete.set(true)
        }

        fun assertFullyStopped() {
            assertFalse(session.isAlive)
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
            assertFalse(Files.exists(paths.agentSessionToken))
        }
    }

    private class RecordingManagedConnection(
        private val delegate: ManagedBusinessAgentConnection,
        scope: CoroutineScope,
        private val traffic: JsonRpcTrafficAudit,
    ) : ManagedBusinessAgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        private val pump: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (text in delegate.incoming) {
                    traffic.recordInbound(text)
                    incomingChannel.send(text)
                }
            } finally {
                incomingChannel.close()
            }
        }

        override val connectionId: String get() = delegate.connectionId
        override val incoming: ReceiveChannel<String> = incomingChannel
        override val state: StateFlow<AgentConnectionState> get() = delegate.state
        override val supervisorState: StateFlow<AgentSupervisorState> get() = delegate.supervisorState
        override val hasConnected: Boolean get() = delegate.hasConnected

        override suspend fun send(text: String) {
            traffic.recordOutbound(text)
            delegate.send(text)
        }

        override suspend fun manualRetry(): Boolean = delegate.manualRetry()

        override suspend fun reconnect(expectedConnectionId: String): Boolean =
            delegate.reconnect(expectedConnectionId)

        override suspend fun close() {
            try {
                delegate.close()
            } finally {
                pump.cancelAndJoin()
                incomingChannel.close()
            }
        }
    }

    private class JsonRpcTrafficAudit(
        private val createSecret: String,
        private val updateSecret: String,
    ) {
        private val monitor = Any()
        private val outboundMethods = mutableListOf<String>()
        private val inboundPayloads = mutableListOf<String>()
        private var createSecretRequestCount = 0
        private var updateSecretRequestCount = 0
        private var outboundSecretViolation = false
        private var inboundSecretLeak = false

        fun recordOutbound(text: String) = synchronized(monitor) {
            val envelope = runCatching { ApplicationProtocol.JSON.parseToJsonElement(text).jsonObject }.getOrNull()
            val method = envelope?.get("method")?.jsonPrimitive?.content.orEmpty()
            outboundMethods += method
            val apiKey = envelope?.get("params")?.jsonObject?.get("apiKey")?.jsonPrimitive?.content
            if (text.contains(createSecret)) {
                if (method == "provider/create" && apiKey == createSecret) createSecretRequestCount += 1
                else outboundSecretViolation = true
            }
            if (text.contains(updateSecret)) {
                if (method == "provider/update" && apiKey == updateSecret) updateSecretRequestCount += 1
                else outboundSecretViolation = true
            }
        }

        fun recordInbound(text: String) = synchronized(monitor) {
            val leaked = text.contains(createSecret) || text.contains(updateSecret)
            inboundSecretLeak = inboundSecretLeak || leaked
            inboundPayloads += text
                .replace(createSecret, "[REDACTED]")
                .replace(updateSecret, "[REDACTED]")
        }

        fun assertSecure() = synchronized(monitor) {
            assertFalse(outboundSecretViolation)
            assertFalse(inboundSecretLeak)
            assertEquals(3, createSecretRequestCount)
            assertEquals(1, updateSecretRequestCount)
            assertEquals(3, outboundMethods.count("provider/create"::equals))
            assertEquals(1, outboundMethods.count("provider/update"::equals))
            assertTrue(inboundPayloads.isNotEmpty())
        }
    }

    private class SecretDiagnosticAudit(
        private val createSecret: String,
        private val updateSecret: String,
    ) {
        private var leaked = false

        @Synchronized
        fun inspect(value: Any?) {
            val diagnostic = value.toString()
            leaked = leaked || diagnostic.contains(createSecret) || diagnostic.contains(updateSecret)
        }

        @Synchronized
        fun assertSecure() {
            assertFalse(leaked)
        }

        @Synchronized
        fun isSecure(): Boolean = !leaked
    }

    private companion object {
        const val STARTUP_TIMEOUT_MILLIS = 30_000L
        const val OVERALL_TIMEOUT_MILLIS = 120_000L
        const val ROOT_SHUTDOWN_TIMEOUT_MILLIS = 20_000L
        const val SESSION_CLOSE_TIMEOUT_MILLIS = 15_000L
        const val DESKTOP_KEYSTORE_PASSWORD = "provider-restart-desktop-secret"
    }
}
