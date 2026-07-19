package com.wzx.huitai.desktop.integration

import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
        var first: RunningDesktop? = null
        var second: RunningDesktop? = null

        try {
            withTimeout(OVERALL_TIMEOUT_MILLIS) {
                first = startDesktop(home, backendJar, traffic)
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

                second = startDesktop(home, backendJar, traffic)
                val secondRunning = requireNotNull(second)
                assertEquals(firstRunning.paths.root, secondRunning.paths.root)
                assertEquals(firstRunning.paths.agentDatabase, secondRunning.paths.agentDatabase)
                assertEquals(firstRunning.paths.agentKeyStore, secondRunning.paths.agentKeyStore)

                val restartedProviders = secondRunning.client.listProviders()
                val restartedRelay = restartedProviders.single { it.id == relayId }
                assertProvider(restartedRelay, updateDraft.copy(apiKey = null), hasApiKey = true)
                assertFalse(restartedProviders.any { it.id == secondaryId })
                assertEquals(expectedFallback, restartedProviders.single(BusinessProvider::active).id)

                val testResult = secondRunning.client.testProvider(relayId)
                assertEquals(relayId, testResult.providerId)
                assertEquals(
                    if (testResult.ok) "Provider 配置可用" else "Provider 配置检查失败",
                    testResult.message,
                )
                diagnostics.inspect(listOf(restartedProviders, restartedRelay, testResult))

                secondRunning.shutdown()
                secondRunning.assertFullyStopped()

                assertSoftDeletedIfSqliteAvailable(firstRunning.paths.agentDatabase, secondaryId)
                assertSecretAbsent(firstRunning.paths.agentDatabase, createSecret, updateSecret)
                assertSecretAbsent(firstRunning.paths.agentKeyStore, createSecret, updateSecret)
                assertSecretAbsent(firstRunning.paths.agentLog, createSecret, updateSecret)
                assertSecretAbsent(firstRunning.paths.desktopLog, createSecret, updateSecret)
            }
        } catch (failure: Throwable) {
            diagnostics.inspect(failure.stackTraceToString())
            if (!diagnostics.isSecure()) {
                throw AssertionError("sensitive marker leaked into failure diagnostics")
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                runCatching { second?.shutdown() }
                runCatching { first?.shutdown() }
                DesktopLoggingBootstrap.resetForTests()
            }
        }

        traffic.assertSecure()
        diagnostics.assertSecure()
    }

    private suspend fun startDesktop(
        home: Path,
        backendJar: Path,
        traffic: JsonRpcTrafficAudit,
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
                parentEnvironment = ::safeParentEnvironment,
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
                        withContext(NonCancellable) {
                            runCatching { recording.close() }
                            runCatching { transport.close() }
                            client.close()
                        }
                    },
                )
            } catch (failure: Throwable) {
                runCatching { transport.close() }
                client.close()
                throw failure
            }
        }
        val factory = ProductionBusinessDesktopCompositionFactory(
            configuration = BusinessDesktopProductionConfiguration(
                home = home,
                backendJar = backendJar,
                desktopSecretBootstrap = DesktopSecretBootstrap { DESKTOP_KEYSTORE_PASSWORD.toCharArray() },
                frameworkDemoIdentity = true,
            ),
            parentScope = scope,
            childLauncher = childLauncher,
            connector = connector,
        )

        return try {
            val root = withTimeout(STARTUP_TIMEOUT_MILLIS) { BusinessDesktopCompositionRoot.start(factory) }
            val view = assertNotNull(root.runtimeView)
            RunningDesktop(
                root = root,
                client = view.production.businessAgentClient,
                session = requireNotNull(capturedSession.get()),
                paths = requireNotNull(capturedPaths.get()),
                scope = scope,
            )
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { capturedSession.get()?.close() }
                scope.cancel()
            }
            throw failure
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

    private fun safeParentEnvironment(): Map<String, String> = SAFE_ENVIRONMENT_KEYS.mapNotNull { key ->
        System.getenv(key)?.takeIf(String::isNotBlank)?.let { key to it }
    }.toMap()

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

    private fun assertSoftDeletedIfSqliteAvailable(database: Path, providerId: String) {
        if (runCatching { Class.forName("org.sqlite.JDBC") }.isFailure) return
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
        if (!Files.isRegularFile(path)) return
        val content = Files.readAllBytes(path)
        secrets.forEach { secret -> assertFalse(content.containsSubsequence(secret.toByteArray())) }
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { offset ->
            candidate.indices.all { index -> this[offset + index] == candidate[index] }
        }
    }

    private class RunningDesktop(
        private val root: BusinessDesktopCompositionRoot,
        val client: BusinessAgentClient,
        val session: BusinessAgentRuntimeSession,
        val paths: BusinessDesktopRuntimePaths,
        private val scope: CoroutineScope,
    ) {
        private val closed = AtomicBoolean(false)
        private val pid = session.childPid

        suspend fun shutdown() {
            if (!closed.compareAndSet(false, true)) return
            withContext(NonCancellable) {
                try {
                    root.shutdown()
                } finally {
                    scope.cancel()
                }
            }
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
            assertEquals(2, createSecretRequestCount)
            assertEquals(1, updateSecretRequestCount)
            assertEquals(2, outboundMethods.count("provider/create"::equals))
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
        const val DESKTOP_KEYSTORE_PASSWORD = "provider-restart-desktop-secret"
        val SAFE_ENVIRONMENT_KEYS = listOf(
            "SystemRoot",
            "WINDIR",
            "TEMP",
            "TMP",
            "TMPDIR",
            "LANG",
            "LC_ALL",
            "PATH",
        )
    }
}
