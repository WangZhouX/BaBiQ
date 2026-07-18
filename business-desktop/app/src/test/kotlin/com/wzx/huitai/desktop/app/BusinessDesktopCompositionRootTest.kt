package com.wzx.huitai.desktop.app

import com.wzx.huitai.action.ActionExecutionContextValidator
import com.wzx.huitai.action.ApplicationActionBus
import com.wzx.huitai.action.port.ActionClock
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.ApplicationSequenceTracker
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.desktop.runtime.ManagedBusinessAgentConnection
import com.wzx.huitai.demo.action.DemoActionCatalog
import com.wzx.huitai.demo.gateway.FakeHuitaiGateway
import com.wzx.huitai.demo.model.DemoScreenModel
import com.wzx.huitai.demo.model.DemoFormEvent
import com.wzx.huitai.desktop.state.BusinessConnectionStatus
import com.wzx.huitai.security.approval.InMemoryApprovalPort
import com.wzx.huitai.security.approval.InMemoryConfirmationPort
import com.wzx.huitai.security.execution.InMemoryActionExecutionStore
import com.wzx.huitai.security.risk.DefaultActionRiskPolicy
import com.wzx.huitai.security.database.BusinessDesktopDatabase
import com.wzx.huitai.security.secret.JceksSecretStore
import com.wzx.huitai.security.secret.SecretRef
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.long

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessDesktopCompositionRootTest {
    @Test
    fun `startup is strictly ordered and user plus agent share one action bus`() = runTest {
        val events = mutableListOf<String>()
        val bus = actionBus()
        val factory = RecordingFactory(events, bus)

        val root = BusinessDesktopCompositionRoot.start(factory)

        assertEquals(
            listOf("lock", "storage", "child", "connection", "identity", "catalog", "context", "ui"),
            events,
        )
        assertSame(bus, root.applicationActionBus)
        assertSame(root.applicationActionBus, root.userActionBus)
        assertSame(root.applicationActionBus, root.agentRequestActionBus)

        root.shutdown()

        assertEquals(
            listOf(
                "lock", "storage", "child", "connection", "identity", "catalog", "context", "ui",
                "close-ui", "close-connection", "close-child", "close-storage", "close-lock",
            ),
            events,
        )
    }

    @Test
    fun `startup failure rolls back every acquired stage in reverse order`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingFactory(events, actionBus(), failAt = "context")

        assertFailsWith<IllegalStateException> {
            BusinessDesktopCompositionRoot.start(factory)
        }

        assertEquals(
            listOf(
                "lock", "storage", "child", "connection", "identity", "catalog", "context",
                "close-connection", "close-child", "close-storage", "close-lock",
            ),
            events,
        )
    }

    @Test
    fun `composition rejects a second bus hidden behind either click or agent handler`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingFactory(events, actionBus(), divergentUiBus = actionBus())

        assertFailsWith<IllegalStateException> {
            BusinessDesktopCompositionRoot.start(factory)
        }

        assertEquals(
            listOf(
                "lock", "storage", "child", "connection", "identity", "catalog", "context", "ui",
                "close-ui", "close-connection", "close-child", "close-storage", "close-lock",
            ),
            events,
        )
    }

    @Test
    fun `production seam keeps real storage bus clients and controllers while child transport is injected`() = runTest {
        val home = Files.createTempDirectory("huitai-production-composition")
        val desktopPassword = "desktop-keystore-test-password".toCharArray()
        val childClosed = mutableListOf<Boolean>()
        val connection = AutoRespondingConnection()
        val factory = ProductionBusinessDesktopCompositionFactory(
            configuration = BusinessDesktopProductionConfiguration(
                home = home,
                backendJar = home.resolve("backend/babiq-server.jar"),
                desktopSecretBootstrap = DesktopSecretBootstrap { desktopPassword.copyOf() },
            ),
            parentScope = this,
            childLauncher = BusinessAgentChildLauncher { context ->
                assertEquals(43, context.backendKeyStorePassword.size)
                val identity = DesktopSessionIdentity.forChildLaunch(context.desktopInstanceId, "http://127.0.0.1")
                BusinessAgentChildHandle(
                    identity = identity,
                    sequenceTracker = ApplicationSequenceTracker(identity.desktopSessionId),
                    resource = CompositionResource { childClosed += true },
                )
            },
            connector = BusinessAgentConnector {
                BusinessAgentConnectionHandle(connection, CompositionResource { connection.close() })
            },
        )

        val root = BusinessDesktopCompositionRoot.start(factory)
        val storage = requireNotNull(root.productionStorage)
        val view = requireNotNull(root.runtimeView)
        val runtimeRoot = home.resolve(".huitai-agent-desktop")
        val databasePath = runtimeRoot.resolve("desktop/data/business-desktop.db")
        val keyStorePath = runtimeRoot.resolve("desktop/secrets/business-desktop.jceks")

        assertEquals("BusinessDesktopDatabase", storage.database::class.simpleName)
        assertTrue(storage.executionStore::class.simpleName == "SQLiteActionExecutionStore")
        assertEquals("JceksSecretStore", storage.secretStore::class.simpleName)
        assertSame(storage.actionBus, root.applicationActionBus)
        assertSame(storage.actionBus, root.userActionBus)
        assertSame(storage.actionBus, root.agentRequestActionBus)
        assertTrue(view.production.actionRequestHandler::class.simpleName == "ApplicationActionRequestHandler")
        assertTrue(view.production.businessAgentClient::class.simpleName == "BusinessAgentClient")
        assertTrue(view.production.workspaceController::class.simpleName == "BusinessWorkspaceController")
        assertTrue(view.desktopState.value.identity != null)
        assertTrue(Files.exists(databasePath))
        assertTrue(Files.exists(keyStorePath))

        val initialMessages = connection.sent.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(1, initialMessages.count { it["method"]?.jsonPrimitive?.content == "application/identity/bind" })
        assertEquals(1, initialMessages.count { it["method"]?.jsonPrimitive?.content == "application/catalog/register" })
        assertEquals(1, initialMessages.count { it["method"]?.jsonPrimitive?.content == "application/context/publish" })
        val catalogRequest = initialMessages.single {
            it["method"]?.jsonPrimitive?.content == "application/catalog/register"
        }
        val actions = catalogRequest.getValue("params").jsonObject
            .getValue("payload").jsonObject
            .getValue("actions").jsonObject
        actions.values.forEach { encoded ->
            val descriptor = encoded.jsonObject
            assertTrue(descriptor.getValue("requiredPermissions").toString().startsWith("["))
            assertTrue(descriptor.getValue("risk").jsonPrimitive.content in setOf(
                "read_only", "reversible_write", "high_risk",
            ))
            assertTrue("inputSchema" in descriptor)
            assertTrue("replayPolicy" in descriptor)
            assertTrue("reconciliationPolicy" in descriptor)
            assertTrue("target" in descriptor)
        }
        val firstContext = initialMessages.single {
            it["method"]?.jsonPrimitive?.content == "application/context/publish"
        }.getValue("params").jsonObject
        assertEquals(1L, firstContext.getValue("contextSequence").jsonPrimitive.long)
        val firstPayload = firstContext.getValue("payload").jsonObject
        assertEquals(1L, firstPayload.getValue("contextRevision").jsonPrimitive.long)
        assertEquals(7, firstPayload.getValue("fields").jsonArray.size)
        assertTrue(firstPayload.getValue("availableActions").jsonArray.isNotEmpty())
        assertEquals(
            "未命名资料",
            firstPayload.getValue("fields").jsonArray
                .map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == "material_name" }
                .getValue("value").jsonPrimitive.content,
        )

        storage.screen.dispatch(DemoFormEvent.EditField("material_name", "第二版"))
        view.production.workspaceController.publishPage(storage.screen.pageContext())
        val contextSequences = connection.sent
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["method"]?.jsonPrimitive?.content == "application/context/publish" }
            .map { it.getValue("params").jsonObject.getValue("contextSequence").jsonPrimitive.long }
        assertEquals(listOf(1L, 2L), contextSequences)
        val latestContextPayload = connection.sent
            .map { Json.parseToJsonElement(it).jsonObject }
            .last { it["method"]?.jsonPrimitive?.content == "application/context/publish" }
            .getValue("params").jsonObject.getValue("payload").jsonObject
        assertEquals(2L, latestContextPayload.getValue("contextRevision").jsonPrimitive.long)
        assertEquals(
            "第二版",
            latestContextPayload.getValue("fields").jsonArray
                .map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == "material_name" }
                .getValue("value").jsonPrimitive.content,
        )

        connection.emitSupervisorState(AgentSupervisorState.Reconnecting(4, 8_000))
        advanceUntilIdle()
        assertEquals(BusinessConnectionStatus.RECONNECTING, view.desktopState.value.connectionStatus)

        connection.emitSupervisorState(AgentSupervisorState.Connected("production-test-connection-2"))
        advanceUntilIdle()
        val republished = connection.sent.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(2, republished.count { it["method"]?.jsonPrimitive?.content == "application/identity/bind" })
        assertEquals(2, republished.count { it["method"]?.jsonPrimitive?.content == "application/catalog/register" })
        assertEquals(3, republished.count { it["method"]?.jsonPrimitive?.content == "application/context/publish" })
        val identitySessions = republished
            .filter { it["method"]?.jsonPrimitive?.content == "application/identity/bind" }
            .map { it.getValue("params").jsonObject.getValue("desktopSessionId").jsonPrimitive.content }
        assertEquals(1, identitySessions.distinct().size)

        root.shutdown()

        assertEquals(listOf(true), childClosed)
        assertEquals(1, connection.closeCount)
        BusinessDesktopDatabase(databasePath).close()
        JceksSecretStore(keyStorePath, desktopPassword.copyOf()).use { reopened ->
            val backendPassword = reopened.load(SecretRef.parse("huitai.backend.keystore.password.v1"))
            assertEquals(43, requireNotNull(backendPassword).size)
            backendPassword.fill('\u0000')
        }
        desktopPassword.fill('\u0000')
    }

    @Test
    fun `managed connection lifecycle preserves exact supervisor states`() = runTest {
        val connection = AutoRespondingConnection()
        val lifecycle = AgentConnectionLifecycleProjection(connection, this)

        connection.emitSupervisorState(AgentSupervisorState.Reconnecting(6, 10_000))
        advanceUntilIdle()
        assertEquals(AgentSupervisorState.Reconnecting(6, 10_000), lifecycle.state.value)
        connection.emitSupervisorState(AgentSupervisorState.ManualRetryRequired)
        advanceUntilIdle()
        assertEquals(AgentSupervisorState.ManualRetryRequired, lifecycle.state.value)
        connection.emitSupervisorState(AgentSupervisorState.AuthenticationFailed)
        advanceUntilIdle()
        assertEquals(AgentSupervisorState.AuthenticationFailed, lifecycle.state.value)
        lifecycle.shutdown()
        advanceUntilIdle()
        assertEquals(AgentSupervisorState.Shutdown, lifecycle.state.value)
    }

    @Test
    fun `production secret bootstrap fails closed and bundled backend path is resource-root relative`() {
        assertFailsWith<IllegalArgumentException> {
            EnvironmentDesktopSecretBootstrap { emptyMap() }.load()
        }
        val root = Files.createTempDirectory("huitai-resources")
        assertEquals(
            root.resolve("backend/babiq-server.jar"),
            BusinessDesktopProductionConfiguration.resolveBundledBackendJar(
                systemProperties = mapOf("huitai.business.resources.root" to root.toString()),
                environment = emptyMap(),
            ),
        )
    }

    private fun actionBus(): ApplicationActionBus {
        val screen = DemoScreenModel()
        val registry = DemoActionCatalog(screen, FakeHuitaiGateway()).createRegistry()
        return ApplicationActionBus(
            registry = registry,
            riskPolicy = DefaultActionRiskPolicy(),
            confirmationPort = InMemoryConfirmationPort(),
            approvalPort = InMemoryApprovalPort(),
            executionStore = InMemoryActionExecutionStore(),
            clock = ActionClock(Instant::now),
            contextValidator = ActionExecutionContextValidator(),
        )
    }

    private class RecordingFactory(
        private val events: MutableList<String>,
        private val bus: ApplicationActionBus,
        private val failAt: String? = null,
        private val divergentUiBus: ApplicationActionBus? = null,
    ) : BusinessDesktopCompositionFactory {
        override suspend fun acquireDesktopLock(): CompositionResource = resource("lock")

        override suspend fun openStorage(): BusinessDesktopStorageAssembly {
            enter("storage")
            return BusinessDesktopStorageAssembly(bus, resource("storage", entered = true))
        }

        override suspend fun launchChild(storage: BusinessDesktopStorageAssembly): CompositionResource =
            resource("child")

        override suspend fun connectAgent(
            storage: BusinessDesktopStorageAssembly,
            child: CompositionResource,
        ): CompositionResource = resource("connection")

        override suspend fun initializeIdentity(connection: CompositionResource) = enter("identity")
        override suspend fun initializeCatalog(connection: CompositionResource) = enter("catalog")
        override suspend fun initializeContext(connection: CompositionResource) = enter("context")

        override suspend fun createUi(
            storage: BusinessDesktopStorageAssembly,
            connection: CompositionResource,
        ): BusinessDesktopUiAssembly {
            enter("ui")
            return BusinessDesktopUiAssembly(
                userActionBus = divergentUiBus ?: bus,
                agentRequestActionBus = bus,
                resource = resource("ui", entered = true),
            )
        }

        private fun resource(stage: String, entered: Boolean = false): CompositionResource {
            if (!entered) enter(stage)
            return CompositionResource { events += "close-$stage" }
        }

        private fun enter(stage: String) {
            events += stage
            if (failAt == stage) throw IllegalStateException("failed at $stage")
        }
    }

    private class AutoRespondingConnection : ManagedBusinessAgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        private var activeConnectionId: String = "production-test-connection"
        override val connectionId: String
            get() = activeConnectionId
        override val incoming: ReceiveChannel<String> = incomingChannel
        val mutableState = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val state: StateFlow<AgentConnectionState> = mutableState
        val mutableSupervisorState = MutableStateFlow<AgentSupervisorState>(
            AgentSupervisorState.Connected(activeConnectionId),
        )
        override val supervisorState: StateFlow<AgentSupervisorState> = mutableSupervisorState
        override val hasConnected: Boolean = true
        val sent = mutableListOf<String>()
        var closeCount: Int = 0
            private set

        override suspend fun send(text: String) {
            sent += text
            val request = Json.parseToJsonElement(text).jsonObject
            val id = request["id"]?.jsonPrimitive?.content ?: return
            incomingChannel.send("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{}}")
        }

        override suspend fun close() {
            closeCount += 1
            incomingChannel.close()
        }

        override suspend fun manualRetry(): Boolean = true

        fun emitSupervisorState(value: AgentSupervisorState) {
            if (value is AgentSupervisorState.Connected) activeConnectionId = value.connectionId
            mutableSupervisorState.value = value
            mutableState.value = when (value) {
                AgentSupervisorState.Idle,
                AgentSupervisorState.Connecting,
                is AgentSupervisorState.Reconnecting,
                AgentSupervisorState.ManualRetryRequired,
                -> AgentConnectionState.Connecting
                is AgentSupervisorState.Connected -> AgentConnectionState.Connected
                AgentSupervisorState.AuthenticationFailed -> AgentConnectionState.AuthenticationFailed
                AgentSupervisorState.Shutdown -> AgentConnectionState.Closed(1000, false)
            }
        }
    }
}
