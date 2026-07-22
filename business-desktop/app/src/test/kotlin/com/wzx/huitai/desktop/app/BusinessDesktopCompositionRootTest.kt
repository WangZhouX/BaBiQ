package com.wzx.huitai.desktop.app

import com.wzx.huitai.action.ActionExecutionContextValidator
import com.wzx.huitai.action.ApplicationActionBus
import com.wzx.huitai.action.port.ActionClock
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.ApplicationSequenceTracker
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.agent.protocol.ActionEnvelope
import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.CommonApplicationFields
import com.wzx.huitai.agent.protocol.JsonRpcRequest
import com.wzx.huitai.desktop.runtime.ManagedBusinessAgentConnection
import com.wzx.huitai.desktop.runtime.BusinessAgentDevelopmentSessionFile
import com.wzx.huitai.desktop.logging.DesktopLoggingBootstrap
import com.wzx.huitai.desktop.decision.ActionDecisionPhase
import com.wzx.huitai.demo.action.DemoActionCatalog
import com.wzx.huitai.demo.gateway.FakeHuitaiGateway
import com.wzx.huitai.demo.model.DemoScreenModel
import com.wzx.huitai.demo.model.DemoFormEvent
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.presentation.context.FieldContext
import com.wzx.huitai.presentation.context.FieldSensitivity
import com.wzx.huitai.presentation.form.FieldChange
import com.wzx.huitai.presentation.form.FormPatch
import com.wzx.huitai.presentation.form.SourceReference
import com.wzx.huitai.desktop.state.BusinessConnectionStatus
import com.wzx.huitai.desktop.state.BusinessAuthenticationStatus
import com.wzx.huitai.desktop.auth.BusinessAccessGateState
import com.wzx.huitai.desktop.security.BusinessAuthSessionMetadataStore
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
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.long
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessDesktopCompositionRootTest {
    @AfterTest
    fun resetDesktopLogging() {
        DesktopLoggingBootstrap.resetForTests()
    }

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
                frameworkDemoIdentity = true,
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
        assertTrue(
            view.production.providerSettingsController::class.simpleName == "BusinessProviderSettingsController",
        )
        assertEquals(
            runtimeRoot.resolve("agent/attachments/clipboard"),
            view.production.agentClipboardAttachmentRoot,
        )
        assertEquals(
            view.production.agentClipboardAttachmentRoot,
            view.production.clipboardImageAttachmentStore.controlledRoot,
        )
        assertTrue(view.production.attachmentPicker::class.simpleName == "BusinessAttachmentPicker")
        assertTrue(view.desktopState.value.identity != null)
        assertTrue(Files.exists(databasePath))
        assertTrue(Files.exists(keyStorePath))

        val providerSettings = view.production.providerSettingsController
        withTimeout(5_000) {
            providerSettings.state.first { it.operationsEnabled && it.providers.isNotEmpty() }
        }

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

        val activeIdentity = requireNotNull(view.desktopState.value.identity)
        val agentSuggestionPatch = FormPatch(
            pageId = DemoFormState.PAGE_ID,
            baseRevision = storage.screen.state.value.revision,
            changes = listOf(
                FieldChange(
                    fieldId = DemoFormState.FIELD_CONTACT,
                    previousValue = JsonPrimitive(storage.screen.state.value.values.contact),
                    newValue = JsonPrimitive("Agent suggested contact"),
                    reason = "Agent preview result",
                    confidence = 0.93,
                    sourceReferences = listOf(SourceReference("agent", "preview-1", "Agent preview")),
                ),
                FieldChange(
                    fieldId = DemoFormState.FIELD_AMOUNT,
                    previousValue = JsonPrimitive(storage.screen.state.value.values.amount),
                    newValue = JsonPrimitive("1500"),
                    reason = "Agent preview result",
                    confidence = 0.88,
                    sourceReferences = listOf(SourceReference("agent", "preview-2", "Agent preview")),
                ),
            ),
        )
        val previewExecutionId = "agent-preview-${System.nanoTime()}"
        connection.serverSend(
            ApplicationProtocol.JSON.encodeToString(
                JsonRpcRequest.serializer(),
                JsonRpcRequest(
                    id = 9001,
                    method = ApplicationMethod.ACTION_REQUEST.wireName,
                    params = ActionEnvelope(
                        common = CommonApplicationFields(
                            protocolVersion = ApplicationProtocol.PROTOCOL_VERSION,
                            desktopInstanceId = activeIdentity.desktopInstanceId,
                            desktopSessionId = activeIdentity.desktopSessionId,
                            authSessionId = activeIdentity.authSessionId,
                            identityEpoch = activeIdentity.identityEpoch,
                            sequence = 9_001,
                            generatedAt = Instant.now().toString(),
                            userId = activeIdentity.userId,
                            tenantId = activeIdentity.tenantId,
                            platformId = activeIdentity.platformId,
                        ),
                        threadId = "thread-preview",
                        turnId = "turn-preview",
                        toolCallId = "tool-preview",
                        executionId = previewExecutionId,
                        payload = buildJsonObject {
                            put("actionId", "form.preview_patch")
                            put("actionVersion", 1)
                            put("input", buildJsonObject {
                                put("executionId", previewExecutionId)
                                put(
                                    "patch",
                                    ApplicationProtocol.JSON.encodeToJsonElement(
                                        FormPatch.serializer(),
                                        agentSuggestionPatch,
                                    ).jsonObject,
                                )
                            })
                            put("pageId", DemoFormState.PAGE_ID)
                            put("contextRevision", agentSuggestionPatch.baseRevision)
                        },
                    ),
                ),
            ),
        )
        val installedSuggestion = withTimeoutOrNull(5_000) {
            storage.screen.state.first { it.suggestionPatch == agentSuggestionPatch }
        }
        assertNotNull(installedSuggestion)
        withTimeout(5_000) {
            view.desktopState.first { DemoFormState.FIELD_CONTACT in it.suggestions }
        }
        assertTrue(
            connection.sent.any { it.contains("\"id\":9001") && it.contains("\"accepted\":true") },
            connection.sent.joinToString("\n"),
        )
        assertEquals(agentSuggestionPatch, storage.screen.state.value.suggestionPatch)
        val projectedSuggestion = view.desktopState.value.suggestions.getValue(DemoFormState.FIELD_CONTACT)
        assertEquals(JsonPrimitive("Agent suggested contact"), projectedSuggestion.value)
        assertEquals("Agent preview", projectedSuggestion.source)
        assertEquals(0.93, projectedSuggestion.confidence)
        storage.screen.dispatch(DemoFormEvent.EditField(DemoFormState.FIELD_CONTACT, "User override"))
        withTimeout(5_000) {
            view.desktopState.first {
                DemoFormState.FIELD_CONTACT !in it.suggestions && DemoFormState.FIELD_AMOUNT in it.suggestions
            }
        }
        assertEquals(
            storage.screen.state.value.revision,
            storage.screen.state.value.suggestionPatch?.baseRevision,
        )

        val sensitiveSnapshot = storage.screen.pageContext().copy(
            revision = 100,
            fields = storage.screen.pageContext().fields + listOf(
                FieldContext(
                    id = "internal_note",
                    label = "Internal note",
                    type = "text",
                    value = JsonPrimitive("sensitive-value"),
                    editable = true,
                    required = false,
                    sensitivity = FieldSensitivity.SENSITIVE,
                ),
                FieldContext(
                    id = "internal_secret",
                    label = "Internal secret",
                    type = "text",
                    value = JsonPrimitive("must-not-leak"),
                    editable = true,
                    required = false,
                    sensitivity = FieldSensitivity.SECRET,
                ),
                FieldContext(
                    id = "api_token",
                    label = "API token",
                    type = "text",
                    value = JsonPrimitive("must-not-leak"),
                    editable = true,
                    required = false,
                    sensitivity = FieldSensitivity.INTERNAL,
                ),
            ),
        )
        view.production.workspaceController.publishPage(sensitiveSnapshot)
        val sanitizedPayload = connection.sent
            .map { Json.parseToJsonElement(it).jsonObject }
            .last { it["method"]?.jsonPrimitive?.content == "application/context/publish" }
            .getValue("params").jsonObject.getValue("payload").jsonObject
        val sanitizedFields = sanitizedPayload.getValue("fields").jsonArray.map { it.jsonObject }
        assertEquals(8, sanitizedFields.size, sanitizedFields.map { it.getValue("id") }.toString())
        assertEquals(
            "[MASKED]",
            sanitizedFields.single { it.getValue("id").jsonPrimitive.content == "internal_note" }
                .getValue("value").jsonPrimitive.content,
        )
        assertTrue(sanitizedFields.none { it.getValue("id").jsonPrimitive.content == "internal_secret" })
        assertTrue(sanitizedFields.none { it.getValue("id").jsonPrimitive.content == "api_token" })

        storage.screen.dispatch(DemoFormEvent.EditField("material_name", "第二版"))
        view.production.workspaceController.publishPage(storage.screen.pageContext())
        val contextSequences = connection.sent
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["method"]?.jsonPrimitive?.content == "application/context/publish" }
            .map { it.getValue("params").jsonObject.getValue("contextSequence").jsonPrimitive.long }
        assertEquals(listOf(1L, 2L, 3L), contextSequences)
        val latestContextPayload = connection.sent
            .map { Json.parseToJsonElement(it).jsonObject }
            .last { it["method"]?.jsonPrimitive?.content == "application/context/publish" }
            .getValue("params").jsonObject.getValue("payload").jsonObject
        assertEquals(3L, latestContextPayload.getValue("contextRevision").jsonPrimitive.long)
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

        connection.contextRegistrationFailuresRemaining = 1
        connection.emitSupervisorState(AgentSupervisorState.Connected("production-test-connection-2"))
        advanceUntilIdle()
        val republished = connection.sent.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(3, republished.count { it["method"]?.jsonPrimitive?.content == "application/identity/bind" })
        assertEquals(3, republished.count { it["method"]?.jsonPrimitive?.content == "application/catalog/register" })
        assertEquals(5, republished.count { it["method"]?.jsonPrimitive?.content == "application/context/publish" })
        val reconnectContexts = republished
            .filter { it["method"]?.jsonPrimitive?.content == "application/context/publish" }
            .takeLast(2)
            .map { it.getValue("params").jsonObject }
        assertEquals(listOf(1L, 1L), reconnectContexts.map { it.getValue("contextSequence").jsonPrimitive.long })
        assertEquals(7, reconnectContexts.last().getValue("payload").jsonObject.getValue("fields").jsonArray.size)
        assertEquals(BusinessConnectionStatus.CONNECTED, view.desktopState.value.connectionStatus)
        val identitySessions = republished
            .filter { it["method"]?.jsonPrimitive?.content == "application/identity/bind" }
            .map { it.getValue("params").jsonObject.getValue("desktopSessionId").jsonPrimitive.content }
        assertEquals(1, identitySessions.distinct().size)

        val contextCountBeforeConcurrentReconnect = connection.sent.count {
            Json.parseToJsonElement(it).jsonObject["method"]?.jsonPrimitive?.content == "application/context/publish"
        }
        val registrationEntered = CompletableDeferred<Unit>()
        val releaseRegistration = CompletableDeferred<Unit>()
        connection.contextRegistrationEntered = registrationEntered
        connection.contextRegistrationRelease = releaseRegistration
        connection.emitSupervisorState(AgentSupervisorState.Reconnecting(1, 1_000))
        connection.emitSupervisorState(AgentSupervisorState.Connected("production-test-connection-4"))
        registrationEntered.await()
        storage.screen.dispatch(DemoFormEvent.EditField(DemoFormState.FIELD_CONTACT, "concurrent edit"))
        val concurrentPublication = async {
            view.production.workspaceController.publishPage(storage.screen.pageContext())
        }
        runCurrent()
        assertFalse(concurrentPublication.isCompleted)
        releaseRegistration.complete(Unit)
        advanceUntilIdle()
        assertTrue(concurrentPublication.await())
        val serializedReconnectContexts = connection.sent
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["method"]?.jsonPrimitive?.content == "application/context/publish" }
            .drop(contextCountBeforeConcurrentReconnect)
            .map { it.getValue("params").jsonObject.getValue("contextSequence").jsonPrimitive.long }
        assertEquals(listOf(1L, 4L), serializedReconnectContexts)

        val decisionResponder = launch {
            view.decisions.state.collect { decisionState ->
                decisionState.activeDialog?.let { dialog ->
                    when (dialog.phase) {
                        ActionDecisionPhase.CONFIRMATION -> view.decisions.accept(dialog.executionId)
                        ActionDecisionPhase.HIGH_RISK_APPROVAL -> view.decisions.approve(dialog.executionId)
                    }
                }
            }
        }
        val saveExecutionId = "save-policy-${System.nanoTime()}"
        view.production.workspaceController.executeUserAction(
            executionId = saveExecutionId,
            actionId = "demo.save_draft",
            actionVersion = 1,
            input = buildJsonObject { put("executionId", saveExecutionId) },
        )
        val submitExecutionId = "submit-policy-${System.nanoTime()}"
        view.production.workspaceController.executeUserAction(
            executionId = submitExecutionId,
            actionId = "demo.submit",
            actionVersion = 1,
            input = buildJsonObject { put("executionId", submitExecutionId) },
        )
        decisionResponder.cancelAndJoin()

        root.shutdown()

        assertFalse(providerSettings.state.value.operationsEnabled)
        assertEquals(listOf(true), childClosed)
        assertEquals(1, connection.closeCount)
        val reopenedDatabase = BusinessDesktopDatabase(databasePath)
        val persistedPolicies = reopenedDatabase.read { sql ->
            sql.prepareStatement(
                "SELECT action_id,replay_policy,reconciliation_policy FROM bd_action_executions " +
                    "WHERE execution_id IN (?,?) ORDER BY action_id",
            ).use { statement ->
                statement.setString(1, saveExecutionId)
                statement.setString(2, submitExecutionId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                Triple(
                                    rows.getString("action_id"),
                                    rows.getString("replay_policy"),
                                    rows.getString("reconciliation_policy"),
                                ),
                            )
                        }
                    }
                }
            }
        }
        reopenedDatabase.close()
        assertEquals(
            listOf(
                Triple("demo.save_draft", "IDEMPOTENCY_KEY_REQUIRED", "QUERY_REMOTE"),
                Triple("demo.submit", "NEVER", "QUERY_REMOTE"),
            ),
            persistedPolicies,
        )
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
    fun `registered lifecycle never exposes connected before bounded reconnect registration succeeds`() = runTest {
        val source = MutableBusinessConnectionLifecycle(AgentSupervisorState.Connected("connection-1"))
        var registrationAttempts = 0
        val lifecycle = RegisteredAgentConnectionLifecycle(
            source = source,
            initialRegisteredConnectionId = "connection-1",
            scope = this,
            maximumRegistrationAttempts = 3,
            retryDelayMillis = { },
            register = {
                registrationAttempts += 1
                if (registrationAttempts <= 3) error("registration unavailable")
            },
        )

        source.mutableState.value = AgentSupervisorState.Reconnecting(1, 1_000)
        advanceUntilIdle()
        source.mutableState.value = AgentSupervisorState.Connected("connection-2")
        advanceUntilIdle()

        assertEquals(3, registrationAttempts)
        assertEquals(AgentSupervisorState.ManualRetryRequired, lifecycle.state.value)
        assertTrue(lifecycle.manualRetry())
        advanceUntilIdle()
        assertEquals(4, registrationAttempts)
        assertEquals(AgentSupervisorState.Connected("connection-5"), lifecycle.state.value)
        lifecycle.shutdown()
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
                systemProperties = mapOf("compose.application.resources.dir" to root.toString()),
                environment = emptyMap(),
            ),
        )
        assertEquals(
            root,
            BusinessDesktopProductionConfiguration.resolveHome(
                environment = mapOf("HUITAI_DESKTOP_HOME" to root.toString()),
            ),
        )
    }

    @Test
    fun `external development mode reads the published session without launching an embedded backend`() = runTest {
        val home = Files.createTempDirectory("huitai-external-backend-composition")
        val paths = com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths.create(home)
        val identity = DesktopSessionIdentity.forChildLaunch(
            desktopInstanceId = java.util.UUID.randomUUID().toString(),
            localOrigin = "http://127.0.0.1",
        )
        val request = com.wzx.huitai.agent.client.AgentConnectRequest(
            "ws://127.0.0.1:49391/ws/agent",
            identity,
        )
        val ownership = BusinessAgentDevelopmentSessionFile.acquireOwnership(paths.agentDevelopmentSession)
        val lease = BusinessAgentDevelopmentSessionFile.publish(
            paths.agentDevelopmentSession,
            request,
            ownership,
        )
        val connection = AutoRespondingConnection()
        var embeddedLaunches = 0
        val factory = ProductionBusinessDesktopCompositionFactory(
            configuration = BusinessDesktopProductionConfiguration(
                home = home,
                backendJar = home.resolve("backend/unused.jar"),
                desktopSecretBootstrap = DesktopSecretBootstrap {
                    "external-development-password".toCharArray()
                },
                frameworkDemoIdentity = true,
                agentLaunchMode = BusinessAgentLaunchMode.ExternalDevelopment,
            ),
            parentScope = this,
            childLauncher = BusinessAgentChildLauncher {
                embeddedLaunches += 1
                error("external mode must not launch an embedded backend")
            },
            connector = BusinessAgentConnector {
                assertEquals(identity.desktopSessionId, it.identity.desktopSessionId)
                BusinessAgentConnectionHandle(connection, CompositionResource { connection.close() })
            },
        )

        val root = BusinessDesktopCompositionRoot.start(factory)

        assertEquals(0, embeddedLaunches)
        assertEquals(identity.desktopSessionId, requireNotNull(root.runtimeView).desktopState.value.identity?.desktopSessionId)
        assertTrue(Files.exists(paths.agentDevelopmentSession), "frontend must not own the backend session file")

        root.shutdown()
        lease.close()
        ownership.close()
    }

    @Test
    fun `packaged production defaults to signed out identity without catalog or context registration`() = runTest {
        val home = Files.createTempDirectory("huitai-signed-out-composition")
        val connection = AutoRespondingConnection()
        val factory = ProductionBusinessDesktopCompositionFactory(
            configuration = BusinessDesktopProductionConfiguration(
                home = home,
                backendJar = home.resolve("backend/babiq-server.jar"),
                desktopSecretBootstrap = DesktopSecretBootstrap { "signed-out-password".toCharArray() },
            ),
            parentScope = this,
            childLauncher = BusinessAgentChildLauncher { context ->
                val session = DesktopSessionIdentity.forChildLaunch(context.desktopInstanceId, "http://127.0.0.1")
                BusinessAgentChildHandle(
                    identity = session,
                    sequenceTracker = ApplicationSequenceTracker(session.desktopSessionId),
                    resource = CompositionResource { },
                )
            },
            connector = BusinessAgentConnector {
                BusinessAgentConnectionHandle(connection, CompositionResource { connection.close() })
            },
        )

        val root = BusinessDesktopCompositionRoot.start(factory)
        advanceUntilIdle()

        val methods = connection.sent.mapNotNull { text ->
            Json.parseToJsonElement(text).jsonObject["method"]?.jsonPrimitive?.content
        }
        assertTrue("application/identity/update" in methods)
        assertFalse("application/identity/bind" in methods)
        assertFalse("application/catalog/register" in methods)
        assertFalse("application/context/publish" in methods)
        val state = requireNotNull(root.runtimeView).desktopState.value
        assertEquals(BusinessAuthenticationStatus.SIGNED_OUT, state.authenticationStatus)
        assertEquals(null, state.identity)
        assertFalse(requireNotNull(root.runtimeView).production.providerSettingsController.state.value.operationsEnabled)
        assertFalse(methods.contains("provider/list"))
        assertEquals(BusinessAccessGateState.SIGNED_OUT, requireNotNull(root.runtimeView).production.authenticationGate.value)
        assertTrue(requireNotNull(root.runtimeView).production.loginController::class.simpleName == "BusinessLoginController")
        val productionStorage = assertNotNull(root.productionStorage)
        assertNull(productionStorage.credentialPersistence.load())
        assertNull(BusinessAuthSessionMetadataStore(productionStorage.secretStore).load())

        val beforeReconnect = connection.sent.size
        requireNotNull(root.runtimeView).production.identityRegistry
            .transitionTo(BusinessAccessGateState.AUTHENTICATING)
        connection.emitSupervisorState(AgentSupervisorState.Reconnecting(1, 0))
        connection.emitSupervisorState(AgentSupervisorState.Connected("production-test-connection-2"))
        advanceUntilIdle()
        val reconnectMethods = connection.sent.drop(beforeReconnect).mapNotNull { text ->
            Json.parseToJsonElement(text).jsonObject["method"]?.jsonPrimitive?.content
        }
        assertEquals(listOf("application/identity/update"), reconnectMethods)

        root.shutdown()
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
        var contextRegistrationFailuresRemaining: Int = 0
        var contextRegistrationEntered: CompletableDeferred<Unit>? = null
        var contextRegistrationRelease: CompletableDeferred<Unit>? = null
        var closeCount: Int = 0
            private set

        override suspend fun send(text: String) {
            sent += text
            val request = Json.parseToJsonElement(text).jsonObject
            val method = request["method"]?.jsonPrimitive?.content
            if (method == "application/context/publish" && contextRegistrationFailuresRemaining > 0) {
                contextRegistrationFailuresRemaining -= 1
                throw IllegalStateException("context registration send failed")
            }
            contextRegistrationRelease?.let { release ->
                contextRegistrationRelease = null
                contextRegistrationEntered?.complete(Unit)
                release.await()
            }
            val id = request["id"]?.jsonPrimitive?.content ?: return
            val result = if (method == "provider/list") {
                """{"providers":[{"id":"relay","displayName":"Relay","type":"OPENAI_COMPATIBLE","authMode":"api_key","baseUrl":"https://relay.example.com/v1","model":"kimi-k3","contextWindow":131072,"enabled":true,"hasApiKey":true,"active":true}]}"""
            } else {
                "{}"
            }
            incomingChannel.send("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}")
        }

        override suspend fun close() {
            closeCount += 1
            incomingChannel.close()
        }

        suspend fun serverSend(text: String) {
            incomingChannel.send(text)
        }

        override suspend fun manualRetry(): Boolean = true

        override suspend fun reconnect(expectedConnectionId: String): Boolean {
            if (activeConnectionId != expectedConnectionId) return false
            val nextOrdinal = activeConnectionId.substringAfterLast('-').toIntOrNull()?.plus(1) ?: 2
            emitSupervisorState(AgentSupervisorState.Reconnecting(1, 0))
            emitSupervisorState(AgentSupervisorState.Connected("production-test-connection-$nextOrdinal"))
            return true
        }

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

    private class MutableBusinessConnectionLifecycle(
        initialState: AgentSupervisorState,
    ) : com.wzx.huitai.desktop.controller.BusinessConnectionLifecycle {
        val mutableState = MutableStateFlow(initialState)
        private var nextConnectionOrdinal: Int = 3
        override val state: StateFlow<AgentSupervisorState> = mutableState
        override suspend fun start() = Unit
        override suspend fun manualRetry(): Boolean = false
        override suspend fun reconnect(expectedConnectionId: String): Boolean {
            if ((mutableState.value as? AgentSupervisorState.Connected)?.connectionId != expectedConnectionId) return false
            mutableState.value = AgentSupervisorState.Reconnecting(1, 0)
            mutableState.value = AgentSupervisorState.Connected("connection-${nextConnectionOrdinal++}")
            return true
        }
        override suspend fun shutdown() {
            mutableState.value = AgentSupervisorState.Shutdown
        }
    }
}
