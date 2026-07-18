package com.wzx.huitai.desktop.controller

import com.wzx.huitai.action.ActionBusResult
import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.agent.conversation.BusinessAgentEvent
import com.wzx.huitai.agent.conversation.BusinessConversationGateway
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderModel
import com.wzx.huitai.agent.conversation.BusinessProviderSelection
import com.wzx.huitai.agent.conversation.BusinessThread
import com.wzx.huitai.agent.conversation.BusinessThreadItem
import com.wzx.huitai.agent.conversation.BusinessTurn
import com.wzx.huitai.desktop.state.BusinessAuthenticationStatus
import com.wzx.huitai.desktop.state.BusinessConnectionStatus
import com.wzx.huitai.desktop.state.BusinessDesktopReducer
import com.wzx.huitai.desktop.state.BusinessDesktopStore
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.presentation.context.PageContextSnapshot
import com.wzx.huitai.presentation.context.PageMode
import com.wzx.huitai.presentation.context.ValidationSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessDesktopCoordinatorTest {
    @Test
    fun `conversation controller owns provider thread turn cancel and chat event flow`() = runTest {
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        store.dispatch(com.wzx.huitai.desktop.state.BusinessDesktopEvent.IdentityAuthenticated(identity(1)))
        val gateway = FakeConversationGateway()
        val controller = BusinessConversationController(gateway, store, this)

        controller.refreshProviders()
        controller.selectProvider("provider-1", "model-1")
        controller.createThread("C:/demo")
        val turn = controller.startTurn("hello")
        gateway.mutableEvents.emit(BusinessAgentEvent.ItemAdded(
            "thread-1",
            turn.id,
            BusinessThreadItem.UserMessage("message-1", "hello"),
        ))
        advanceUntilIdle()

        assertEquals("provider-1", controller.state.value.activeProviderId)
        assertEquals("thread-1", controller.state.value.currentThread?.id)
        assertEquals("provider-1", gateway.startedWithProvider)
        assertEquals("hello", (controller.state.value.messages.single() as BusinessThreadItem.UserMessage).text)
        assertTrue(controller.cancelActiveTurn())
        assertEquals("turn-1", gateway.canceledTurnId)
        controller.close()
    }

    @Test
    fun `authenticated lifecycle registers identity catalog then publishes context and coalesces duplicate revision`() = runTest {
        val calls = mutableListOf<String>()
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val connection = FakeConnectionLifecycle()
        val registration = object : BusinessRegistrationPort {
            override suspend fun bindIdentity(identity: BusinessIdentity) { calls += "identity:${identity.identityEpoch}" }
            override suspend fun registerCatalog(identity: BusinessIdentity, catalogEpoch: Long) { calls += "catalog:$catalogEpoch" }
        }
        val publication = BusinessContextPublicationPort { _, catalogEpoch, sequence, snapshot ->
            calls += "context:$catalogEpoch:$sequence:${snapshot.revision}"
        }
        val actions = RecordingActionPort()
        val workspace = BusinessWorkspaceController(store, publication, actions)
        val coordinator = BusinessDesktopCoordinator(store, connection, registration, workspace, this)

        coordinator.start()
        connection.mutableState.value = AgentSupervisorState.Connected("connection-1")
        advanceUntilIdle()
        coordinator.onAuthenticated(identity(1), catalogEpoch = 7, initialPage = page(3))
        workspace.publishPage(page(3))

        assertEquals(listOf("identity:1", "catalog:7", "context:7:1:3"), calls)
        assertEquals(BusinessConnectionStatus.CONNECTED, coordinator.state.value.connectionStatus)
        assertEquals(3, coordinator.state.value.page?.revision)

        val result = workspace.executeUserAction(
            executionId = "user-exec-1",
            actionId = "form.read_state",
            actionVersion = 1,
            input = buildJsonObject { put("safe", true) },
        )
        assertTrue(result is ActionBusResult.Rejected)
        assertEquals(com.wzx.huitai.action.model.ActionOrigin.USER, actions.command?.origin)
        assertEquals(3, actions.command?.contextRevision)
        assertEquals(actions.command?.identityScope, actions.context?.identityScope)
        coordinator.shutdown()
    }

    @Test
    fun `attaching an already published snapshot suppresses duplicate and continues at next context sequence`() = runTest {
        val calls = mutableListOf<String>()
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val workspace = BusinessWorkspaceController(
            store,
            BusinessContextPublicationPort { _, _, sequence, snapshot ->
                calls += "$sequence:${snapshot.revision}"
            },
            RecordingActionPort(),
        )

        workspace.attachPublishedIdentity(
            identity = identity(1),
            catalogEpoch = 3,
            snapshot = page(7),
            lifecycleGeneration = 1,
            publishedContextSequence = 1,
        )

        assertFalse(workspace.publishPage(page(7)))
        assertTrue(workspace.publishPage(page(8)))
        assertEquals(listOf("2:8"), calls)
        assertEquals(8, store.state.value.page?.revision)
    }

    @Test
    fun `disconnect reconnect manual retry expiry and shutdown are reflected without restarting old scope`() = runTest {
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val connection = FakeConnectionLifecycle()
        val workspace = BusinessWorkspaceController(
            store,
            BusinessContextPublicationPort { _, _, _, _ -> },
            RecordingActionPort(),
        )
        val coordinator = BusinessDesktopCoordinator(
            store,
            connection,
            object : BusinessRegistrationPort {
                override suspend fun bindIdentity(identity: BusinessIdentity) = Unit
                override suspend fun registerCatalog(identity: BusinessIdentity, catalogEpoch: Long) = Unit
            },
            workspace,
            this,
        )

        coordinator.start()
        connection.mutableState.value = AgentSupervisorState.Reconnecting(2, 1_000)
        advanceUntilIdle()
        assertEquals(BusinessConnectionStatus.RECONNECTING, coordinator.state.value.connectionStatus)
        connection.mutableState.value = AgentSupervisorState.ManualRetryRequired
        advanceUntilIdle()
        assertEquals(BusinessConnectionStatus.MANUAL_RETRY_REQUIRED, coordinator.state.value.connectionStatus)
        assertTrue(coordinator.manualRetry())
        assertEquals(1, connection.manualRetries)

        coordinator.onAuthenticated(identity(1), 1, page(1))
        coordinator.onMembershipExpired()
        assertEquals(BusinessAuthenticationStatus.MEMBERSHIP_EXPIRED, coordinator.state.value.authenticationStatus)
        assertFalse(workspace.hasActiveIdentity)

        coordinator.onAuthenticationExpired()
        assertEquals(BusinessAuthenticationStatus.EXPIRED, coordinator.state.value.authenticationStatus)
        coordinator.shutdown()
        advanceUntilIdle()
        assertEquals(BusinessConnectionStatus.SHUTDOWN, coordinator.state.value.connectionStatus)
        assertEquals(1, connection.shutdowns)
    }

    @Test
    fun `concurrent identity changes cannot interleave registration order`() = runTest {
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val connection = FakeConnectionLifecycle()
        val firstBound = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val registration = object : BusinessRegistrationPort {
            override suspend fun bindIdentity(identity: BusinessIdentity) {
                calls += "bind:${identity.identityEpoch}"
                if (identity.identityEpoch == 1L) {
                    firstBound.complete(Unit)
                    releaseFirst.await()
                }
            }
            override suspend fun registerCatalog(identity: BusinessIdentity, catalogEpoch: Long) {
                calls += "catalog:${identity.identityEpoch}"
            }
        }
        val workspace = BusinessWorkspaceController(
            store,
            BusinessContextPublicationPort { identity, _, _, _ -> calls += "context:${identity.identityEpoch}" },
            RecordingActionPort(),
        )
        val coordinator = BusinessDesktopCoordinator(store, connection, registration, workspace, this)

        val first = async { coordinator.onAuthenticated(identity(1), 1, page(1)) }
        firstBound.await()
        val second = async { coordinator.onAuthenticated(identity(2), 2, page(2)) }
        advanceUntilIdle()
        assertEquals(listOf("bind:1"), calls)
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(
            listOf("bind:1", "catalog:1", "context:1", "bind:2", "catalog:2", "context:2"),
            calls,
        )
        assertEquals(2, coordinator.state.value.identity?.identityEpoch)
    }

    @Test
    fun `membership expiry invalidates blocked registration without waiting or restoring stale identity`() = runTest {
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val connection = FakeConnectionLifecycle()
        val bindEntered = CompletableDeferred<Unit>()
        val releaseBind = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val registration = object : BusinessRegistrationPort {
            override suspend fun bindIdentity(identity: BusinessIdentity) {
                calls += "bind"
                bindEntered.complete(Unit)
                releaseBind.await()
            }
            override suspend fun registerCatalog(identity: BusinessIdentity, catalogEpoch: Long) { calls += "catalog" }
        }
        val workspace = BusinessWorkspaceController(
            store,
            BusinessContextPublicationPort { _, _, _, _ -> calls += "context" },
            RecordingActionPort(),
        )
        val coordinator = BusinessDesktopCoordinator(store, connection, registration, workspace, this)
        val authentication = async { coordinator.onAuthenticated(identity(1), 1, page(1)) }
        bindEntered.await()

        withTimeout(500) { coordinator.onMembershipExpired() }
        assertNull(coordinator.state.value.identity)
        assertFalse(workspace.hasActiveIdentity)
        releaseBind.complete(Unit)
        authentication.await()

        assertEquals(listOf("bind"), calls)
        assertNull(coordinator.state.value.identity)
    }

    @Test
    fun `expiry clears local identity while context publication is blocked and stale publication cannot commit`() = runTest {
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val publishEntered = CompletableDeferred<Unit>()
        val releasePublish = CompletableDeferred<Unit>()
        val workspace = BusinessWorkspaceController(
            store,
            BusinessContextPublicationPort { _, _, _, _ -> publishEntered.complete(Unit); releasePublish.await() },
            RecordingActionPort(),
        )
        val coordinator = BusinessDesktopCoordinator(
            store,
            FakeConnectionLifecycle(),
            object : BusinessRegistrationPort {
                override suspend fun bindIdentity(identity: BusinessIdentity) = Unit
                override suspend fun registerCatalog(identity: BusinessIdentity, catalogEpoch: Long) = Unit
            },
            workspace,
            this,
        )
        val authentication = async { coordinator.onAuthenticated(identity(1), 1, page(1)) }
        publishEntered.await()
        assertEquals(1, coordinator.state.value.identity?.identityEpoch)

        withTimeout(500) { coordinator.onAuthenticationExpired() }
        assertNull(coordinator.state.value.identity)
        assertNull(coordinator.state.value.page)
        releasePublish.complete(Unit)
        authentication.await()

        assertNull(coordinator.state.value.identity)
        assertNull(coordinator.state.value.page)
    }

    @Test
    fun `shutdown wins a concurrent start and later manual retry is rejected locally`() = runTest {
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val connection = BlockingStartConnectionLifecycle()
        val workspace = BusinessWorkspaceController(
            store,
            BusinessContextPublicationPort { _, _, _, _ -> },
            RecordingActionPort(),
        )
        val coordinator = BusinessDesktopCoordinator(
            store,
            connection,
            object : BusinessRegistrationPort {
                override suspend fun bindIdentity(identity: BusinessIdentity) = Unit
                override suspend fun registerCatalog(identity: BusinessIdentity, catalogEpoch: Long) = Unit
            },
            workspace,
            this,
        )

        val start = async { coordinator.start() }
        connection.startEntered.await()
        withTimeout(500) { coordinator.shutdown() }
        assertEquals(BusinessConnectionStatus.SHUTDOWN, coordinator.state.value.connectionStatus)
        assertFalse(coordinator.manualRetry())
        assertEquals(0, connection.manualRetries)
        connection.releaseStart.complete(Unit)
        start.await()
        advanceUntilIdle()
        assertEquals(BusinessConnectionStatus.SHUTDOWN, coordinator.state.value.connectionStatus)
        assertEquals(AgentSupervisorState.Shutdown, connection.state.value)
        assertEquals(2, connection.shutdowns)
    }

    @Test
    fun `shutdown racing manual retry performs post cleanup and reports retry rejected`() = runTest {
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val connection = BlockingManualRetryConnectionLifecycle()
        val workspace = BusinessWorkspaceController(
            store,
            BusinessContextPublicationPort { _, _, _, _ -> },
            RecordingActionPort(),
        )
        val coordinator = BusinessDesktopCoordinator(
            store,
            connection,
            object : BusinessRegistrationPort {
                override suspend fun bindIdentity(identity: BusinessIdentity) = Unit
                override suspend fun registerCatalog(identity: BusinessIdentity, catalogEpoch: Long) = Unit
            },
            workspace,
            this,
        )

        val retry = async { coordinator.manualRetry() }
        connection.retryEntered.await()
        coordinator.shutdown()
        connection.releaseRetry.complete(Unit)

        assertFalse(retry.await())
        assertEquals(AgentSupervisorState.Shutdown, connection.state.value)
        assertEquals(2, connection.shutdowns)
    }

    @Test
    fun `shutdown invalidates blocked catalog registration without waiting or publishing context`() = runTest {
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val catalogEntered = CompletableDeferred<Unit>()
        val releaseCatalog = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val workspace = BusinessWorkspaceController(
            store,
            BusinessContextPublicationPort { _, _, _, _ -> calls += "context" },
            RecordingActionPort(),
        )
        val coordinator = BusinessDesktopCoordinator(
            store,
            FakeConnectionLifecycle(),
            object : BusinessRegistrationPort {
                override suspend fun bindIdentity(identity: BusinessIdentity) { calls += "bind" }
                override suspend fun registerCatalog(identity: BusinessIdentity, catalogEpoch: Long) {
                    calls += "catalog"
                    catalogEntered.complete(Unit)
                    releaseCatalog.await()
                }
            },
            workspace,
            this,
        )
        val authentication = async { coordinator.onAuthenticated(identity(1), 1, page(1)) }
        catalogEntered.await()

        withTimeout(500) { coordinator.shutdown() }
        assertEquals(BusinessConnectionStatus.SHUTDOWN, coordinator.state.value.connectionStatus)
        assertNull(coordinator.state.value.identity)
        releaseCatalog.complete(Unit)
        authentication.await()

        assertEquals(listOf("bind", "catalog"), calls)
        assertNull(coordinator.state.value.page)
    }

    private class FakeConnectionLifecycle : BusinessConnectionLifecycle {
        val mutableState = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Idle)
        override val state = mutableState
        var starts = 0
        var manualRetries = 0
        var shutdowns = 0
        override suspend fun start() { starts++ }
        override suspend fun manualRetry(): Boolean { manualRetries++; return true }
        override suspend fun shutdown() { shutdowns++; mutableState.value = AgentSupervisorState.Shutdown }
    }

    private class BlockingStartConnectionLifecycle : BusinessConnectionLifecycle {
        override val state = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Idle)
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        var manualRetries = 0
        var shutdowns = 0
        override suspend fun start() {
            startEntered.complete(Unit)
            releaseStart.await()
            state.value = AgentSupervisorState.Connected("late")
        }
        override suspend fun manualRetry(): Boolean { manualRetries++; return true }
        override suspend fun shutdown() { shutdowns++; state.value = AgentSupervisorState.Shutdown }
    }

    private class BlockingManualRetryConnectionLifecycle : BusinessConnectionLifecycle {
        override val state = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.ManualRetryRequired)
        val retryEntered = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        var shutdowns = 0
        override suspend fun start() = Unit
        override suspend fun manualRetry(): Boolean {
            retryEntered.complete(Unit)
            releaseRetry.await()
            state.value = AgentSupervisorState.Connected("late-retry")
            return true
        }
        override suspend fun shutdown() { shutdowns++; state.value = AgentSupervisorState.Shutdown }
    }

    private class FakeConversationGateway : BusinessConversationGateway {
        val mutableEvents = MutableSharedFlow<BusinessAgentEvent>()
        override val events: Flow<BusinessAgentEvent> = mutableEvents
        var startedWithProvider: String? = null
        var canceledTurnId: String? = null
        override suspend fun listProviders(): List<BusinessProvider> = listOf(
            BusinessProvider(
                id = "provider-1",
                displayName = "Provider One",
                models = listOf(BusinessProviderModel("model-1", "Model One", true)),
                authMode = "api_key",
                hasApiKey = true,
                active = true,
            ),
        )
        override suspend fun setActiveProvider(providerId: String, modelId: String?): BusinessProviderSelection =
            BusinessProviderSelection(providerId, requireNotNull(modelId))
        override suspend fun createThread(cwd: String): BusinessThread = BusinessThread("thread-1", "demo", cwd)
        override suspend fun startTurn(threadId: String, text: String, providerId: String?): BusinessTurn {
            startedWithProvider = providerId
            return BusinessTurn("turn-1", threadId)
        }
        override suspend fun cancelTurn(turnId: String): Boolean {
            canceledTurnId = turnId
            return true
        }
        override fun close() = Unit
    }

    private class RecordingActionPort : UserApplicationActionPort {
        var command: ActionCommand? = null
        var context: ActionContext? = null
        override suspend fun execute(command: ActionCommand, context: ActionContext): ActionBusResult {
            this.command = command
            this.context = context
            return ActionBusResult.Rejected(ActionError(ActionErrorCode.ACTION_NOT_FOUND, "test"))
        }
    }

    private fun identity(epoch: Long) = BusinessIdentity(
        desktopInstanceId = "desktop-1",
        desktopSessionId = "desktop-session-1",
        authSessionId = "auth-$epoch",
        identityEpoch = epoch,
        userId = "user-$epoch",
        tenantId = "tenant-$epoch",
        platformId = "platform-1",
        roles = setOf("user"),
        permissions = setOf("framework:read"),
    )

    private fun page(revision: Long) = PageContextSnapshot(
        snapshotId = "snapshot-$revision",
        pageId = "demo-form",
        pageTitle = "Demo",
        route = "/demo",
        revision = revision,
        mode = PageMode.EDIT,
        validationSummary = ValidationSummary(true),
    )
}
