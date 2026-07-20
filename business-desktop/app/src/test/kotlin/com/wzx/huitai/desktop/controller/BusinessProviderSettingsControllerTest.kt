package com.wzx.huitai.desktop.controller

import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.agent.conversation.BusinessAgentEvent
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.conversation.BusinessConversationGateway
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderDeleteResult
import com.wzx.huitai.agent.conversation.BusinessProviderDraft
import com.wzx.huitai.agent.conversation.BusinessProviderModel
import com.wzx.huitai.agent.conversation.BusinessProviderOAuthLoginResult
import com.wzx.huitai.agent.conversation.BusinessProviderOAuthStatus
import com.wzx.huitai.agent.conversation.BusinessProviderSelection
import com.wzx.huitai.agent.conversation.BusinessProviderTestResult
import com.wzx.huitai.agent.conversation.BusinessThread
import com.wzx.huitai.agent.conversation.BusinessTurn
import com.wzx.huitai.desktop.state.BusinessAuthenticationStatus
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessIdentity
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessProviderSettingsControllerTest {
    @Test
    fun `state and notices never expose api key fields or secret text`() {
        val marker = "sk-fake-sensitive-marker"
        val state = BusinessProviderSettingsState(
            providers = listOf(provider("relay")),
            notice = BusinessProviderSettingsNotice("SAFE", "安全提示", BusinessProviderSettingsNoticeLevel.INFO),
        )

        assertNull(state::class.members.singleOrNull { it.name == "apiKey" })
        assertNull(state.notice!!::class.members.singleOrNull { it.name == "apiKey" })
        assertFalse(state.toString().contains(marker))
        assertFalse(state.notice.toString().contains(marker))
        assertFalse(BusinessProviderSettingsState().operationsEnabled)
        assertEquals(0, BusinessProviderSettingsState().connectionGeneration)
    }

    @Test
    fun `operations enable only for finalized authenticated connections and generation is stable per id`() = runTest {
        val supervisor = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connecting)
        val desktop = MutableStateFlow(BusinessDesktopState())
        val gateway = FakeGateway()
        val controller = controller(gateway, supervisor, desktop)
        val draft = draft("sk-fake-sensitive-marker")

        controller.refresh()
        controller.create(draft)
        controller.update(draft)
        controller.delete("relay")
        controller.test("relay")
        controller.setActive("relay", "kimi-k3")
        controller.oauthStatus("claude")
        controller.oauthLogin("claude")
        assertTrue(gateway.calls.isEmpty())
        assertEquals("PROVIDER_SETTINGS_UNAVAILABLE", controller.state.value.notice?.code)
        assertFalse(controller.state.value.toString().contains("sk-fake-sensitive-marker"))

        supervisor.value = AgentSupervisorState.Connected("connection-1")
        runCurrent()
        assertFalse(controller.state.value.operationsEnabled)
        controller.create(draft)
        assertTrue(gateway.calls.isEmpty())
        desktop.value = authenticatedState()
        advanceUntilIdle()
        assertTrue(controller.state.value.operationsEnabled)
        assertEquals(1, controller.state.value.connectionGeneration)
        assertEquals(listOf("list"), gateway.calls)

        desktop.value = authenticatedState().copy(error = com.wzx.huitai.desktop.state.BusinessDesktopError("X", "safe"))
        runCurrent()
        assertEquals(1, controller.state.value.connectionGeneration)

        supervisor.value = AgentSupervisorState.Reconnecting(1, 1_000)
        runCurrent()
        assertFalse(controller.state.value.operationsEnabled)
        controller.delete("relay")
        assertEquals(1, gateway.calls.count { it == "list" })
        supervisor.value = AgentSupervisorState.Connected("connection-1")
        runCurrent()
        assertTrue(controller.state.value.operationsEnabled)
        assertEquals(1, controller.state.value.connectionGeneration)

        supervisor.value = AgentSupervisorState.Reconnecting(1, 1_000)
        supervisor.value = AgentSupervisorState.Connected("connection-2")
        advanceUntilIdle()
        assertEquals(2, controller.state.value.connectionGeneration)
        assertEquals(2, gateway.calls.count { it == "list" })
        controller.close()
    }

    @Test
    fun `mutations refresh settings and conversation while delete and selection use active ids`() = runTest {
        val supervisor = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connected("connection-1"))
        val desktop = MutableStateFlow(authenticatedState())
        val gateway = FakeGateway()
        val callbackProviders = mutableListOf<List<BusinessProvider>>()
        val controller = controller(gateway, supervisor, desktop) { callbackProviders += it }
        advanceUntilIdle()
        gateway.calls.clear()

        val relay = provider("relay", active = false)
        gateway.providers = listOf(relay)
        assertEquals(relay, controller.create(draft()))
        assertEquals(listOf("create:relay", "list"), gateway.calls)
        assertEquals(1, callbackProviders.size)
        assertEquals(listOf(relay), controller.state.value.providers)
        assertEquals(controller.state.value.providers, callbackProviders.last())

        gateway.calls.clear()
        gateway.providers = listOf(relay.copy(model = "kimi-k3-updated"))
        assertEquals("kimi-k3", requireNotNull(controller.update(draft())).model)
        assertEquals(listOf("update:relay", "list"), gateway.calls)
        assertEquals(2, callbackProviders.size)
        assertEquals(controller.state.value.providers, callbackProviders.last())

        val fallback = provider("fallback", active = false)
        gateway.calls.clear()
        gateway.providers = listOf(fallback)
        gateway.deleteResult = BusinessProviderDeleteResult(true, "relay", "fallback")
        assertEquals("fallback", controller.delete("relay")?.activeProviderId)
        assertEquals(listOf("delete:relay", "list"), gateway.calls)
        assertTrue(controller.state.value.providers.single().active)
        assertEquals(3, callbackProviders.size)
        assertEquals(controller.state.value.providers, callbackProviders.last())

        val claude = provider("claude", active = false, type = "ANTHROPIC", authMode = "oauth_cli")
        gateway.calls.clear()
        gateway.providers = listOf(fallback, claude)
        assertEquals("claude", controller.setActive("claude", "claude-sonnet")?.providerId)
        assertEquals(listOf("active:claude:claude-sonnet", "list"), gateway.calls)
        assertTrue(controller.state.value.providers.single { it.id == "claude" }.active)
        assertEquals(4, callbackProviders.size)
        assertEquals(controller.state.value.providers, callbackProviders.last())

        gateway.calls.clear()
        assertTrue(controller.test("claude")!!.ok)
        assertEquals("Provider 配置可用", controller.state.value.notice?.message)
        assertEquals(listOf("test:claude"), gateway.calls)

        controller.oauthStatus("fallback")
        assertEquals(listOf("test:claude"), gateway.calls)
        assertEquals("PROVIDER_OAUTH_UNAVAILABLE", controller.state.value.notice?.code)
        val status = controller.oauthStatus("claude")
        assertEquals(listOf("test:claude", "oauth-status"), gateway.calls)
        assertEquals(status, controller.state.value.oauthStatus["claude"])
        assertEquals("未登录", controller.state.value.notice?.message)
        assertTrue(controller.oauthLogin("claude")!!.ok)
        assertEquals("登录已启动", controller.state.value.notice?.message)
        controller.close()
    }

    @Test
    fun `busy state clears failures are safe cancellation propagates and close owns no gateway`() = runTest {
        val marker = "sk-fake-sensitive-marker"
        val supervisor = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connected("connection-1"))
        val desktop = MutableStateFlow(authenticatedState())
        val gateway = FakeGateway()
        val controller = controller(gateway, supervisor, desktop)
        advanceUntilIdle()
        gateway.calls.clear()

        gateway.listStarted = CompletableDeferred()
        gateway.listRelease = CompletableDeferred()
        val refreshing = async { controller.refresh() }
        gateway.listStarted!!.await()
        runCurrent()
        assertTrue(controller.state.value.loading)
        gateway.listRelease!!.complete(Unit)
        refreshing.await()
        assertFalse(controller.state.value.loading)
        gateway.calls.clear()

        gateway.createStarted = CompletableDeferred()
        gateway.createRelease = CompletableDeferred()
        val creating = async { controller.create(draft(marker)) }
        gateway.createStarted!!.await()
        runCurrent()
        assertEquals("relay", controller.state.value.busyProviderId)
        gateway.createRelease!!.complete(Unit)
        creating.await()
        assertNull(controller.state.value.busyProviderId)

        gateway.failure = IllegalStateException(marker)
        assertNull(controller.update(draft(marker)))
        assertEquals("PROVIDER_SETTINGS_FAILED", controller.state.value.notice?.code)
        assertEquals("Provider 设置操作失败", controller.state.value.notice?.message)
        assertFalse(controller.state.value.toString().contains(marker))
        assertNull(controller.state.value.busyProviderId)

        gateway.failure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { controller.delete("relay") }
        assertNull(controller.state.value.busyProviderId)

        controller.close()
        controller.close()
        supervisor.value = AgentSupervisorState.Reconnecting(1, 1_000)
        desktop.value = BusinessDesktopState()
        advanceUntilIdle()
        assertEquals(0, gateway.closeCount)
        assertFalse(controller.state.value.operationsEnabled)
    }

    @Test
    fun `old generation list create delete and test results cannot commit or notify conversation`() = runTest {
        val supervisor = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connected("connection-1"))
        val desktop = MutableStateFlow(authenticatedState())
        val gateway = FakeGateway()
        val callbacks = AtomicInteger()
        val controller = controller(gateway, supervisor, desktop) { callbacks.incrementAndGet() }
        advanceUntilIdle()
        val initialProviders = controller.state.value.providers
        gateway.calls.clear()

        gateway.providers = listOf(provider("stale-list"))
        gateway.listStarted = CompletableDeferred()
        gateway.listRelease = CompletableDeferred()
        val refreshing = async { controller.refresh() }
        gateway.listStarted!!.await()
        supervisor.value = AgentSupervisorState.Reconnecting(1, 1_000)
        runCurrent()
        gateway.listRelease!!.complete(Unit)
        assertNull(refreshing.await())
        assertEquals(initialProviders, controller.state.value.providers)
        assertEquals(0, callbacks.get())

        gateway.listStarted = null
        gateway.listRelease = null
        gateway.providers = listOf(provider("connection-2"))
        supervisor.value = AgentSupervisorState.Connected("connection-2")
        advanceUntilIdle()
        gateway.calls.clear()

        gateway.createStarted = CompletableDeferred()
        gateway.createRelease = CompletableDeferred()
        val creating = async { controller.create(draft()) }
        gateway.createStarted!!.await()
        supervisor.value = AgentSupervisorState.Reconnecting(1, 1_000)
        supervisor.value = AgentSupervisorState.Connected("connection-3")
        gateway.providers = listOf(provider("connection-3"))
        runCurrent()
        gateway.createRelease!!.complete(Unit)
        assertNull(creating.await())
        advanceUntilIdle()
        assertEquals(listOf(provider("connection-3")), controller.state.value.providers)
        assertEquals(0, callbacks.get())
        assertEquals(1, gateway.calls.count { it == "list" })
        assertTrue(controller.state.value.notice?.code != "PROVIDER_CREATED")

        gateway.createStarted = null
        gateway.createRelease = null
        gateway.calls.clear()
        gateway.deleteStarted = CompletableDeferred()
        gateway.deleteRelease = CompletableDeferred()
        val deleting = async { controller.delete("connection-3") }
        gateway.deleteStarted!!.await()
        supervisor.value = AgentSupervisorState.Reconnecting(1, 1_000)
        runCurrent()
        gateway.deleteRelease!!.complete(Unit)
        assertNull(deleting.await())
        assertEquals(0, callbacks.get())
        assertTrue(controller.state.value.notice?.code != "PROVIDER_DELETED")

        gateway.deleteStarted = null
        gateway.deleteRelease = null
        supervisor.value = AgentSupervisorState.Connected("connection-4")
        advanceUntilIdle()
        gateway.calls.clear()
        gateway.testStarted = CompletableDeferred()
        gateway.testRelease = CompletableDeferred()
        val testing = async { controller.test("connection-3") }
        gateway.testStarted!!.await()
        supervisor.value = AgentSupervisorState.Reconnecting(1, 1_000)
        runCurrent()
        gateway.testRelease!!.complete(Unit)
        assertNull(testing.await())
        assertTrue(controller.state.value.notice?.code != "PROVIDER_TEST_SUCCEEDED")
        controller.close()
    }

    @Test
    fun `caller cancellation cancels owned operation and close rejects late cancellation ignoring result`() = runTest {
        val supervisor = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connected("connection-1"))
        val desktop = MutableStateFlow(authenticatedState())
        val gateway = FakeGateway()
        val callbacks = AtomicInteger()
        val controller = controller(gateway, supervisor, desktop) { callbacks.incrementAndGet() }
        advanceUntilIdle()
        val initialProviders = controller.state.value.providers
        gateway.calls.clear()

        gateway.createStarted = CompletableDeferred()
        gateway.createRelease = CompletableDeferred()
        gateway.ignoreCreateCancellation = true
        gateway.providers = listOf(provider("caller-late"))
        val caller = launch { controller.create(draft()) }
        gateway.createStarted!!.await()
        caller.cancel()
        caller.join()
        assertEquals(1, gateway.createCancellationCount)
        gateway.createRelease!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(initialProviders, controller.state.value.providers)
        assertEquals(0, callbacks.get())
        assertTrue(controller.state.value.notice?.code != "PROVIDER_CREATED")

        gateway.createStarted = CompletableDeferred()
        gateway.createRelease = CompletableDeferred()
        gateway.providers = listOf(provider("late"))
        val lateCreating = async { controller.create(draft()) }
        gateway.createStarted!!.await()
        controller.close()
        controller.close()
        runCurrent()
        gateway.createRelease!!.complete(Unit)
        assertFailsWith<CancellationException> { lateCreating.await() }
        advanceUntilIdle()
        assertEquals(initialProviders, controller.state.value.providers)
        assertEquals(0, callbacks.get())
        assertTrue(controller.state.value.notice?.code != "PROVIDER_CREATED")
        assertEquals(0, gateway.closeCount)
    }

    @Test
    fun `new generation auto refresh does not wait for old cancellation ignoring list`() = runTest {
        val supervisor = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connected("connection-1"))
        val desktop = MutableStateFlow(authenticatedState())
        val gateway = FakeGateway()
        val controller = controller(gateway, supervisor, desktop)
        advanceUntilIdle()

        val oldProviders = listOf(provider("old-generation"))
        val newProviders = listOf(provider("new-generation"))
        gateway.providers = oldProviders
        gateway.listStarted = CompletableDeferred()
        gateway.listRelease = CompletableDeferred()
        gateway.remainingGatedListCalls = 1
        gateway.ignoreListCancellation = true
        val oldRefresh = async { controller.refresh() }
        gateway.listStarted!!.await()

        try {
            gateway.ungatedListStarted = CompletableDeferred()
            gateway.providers = newProviders
            supervisor.value = AgentSupervisorState.Reconnecting(1, 1_000)
            runCurrent()
            supervisor.value = AgentSupervisorState.Connected("connection-2")

            withTimeout(1_000) { gateway.ungatedListStarted!!.await() }
            withTimeout(1_000) { controller.state.first { it.providers == newProviders } }
            assertFalse(oldRefresh.isCompleted)
        } finally {
            gateway.listRelease!!.complete(Unit)
        }

        assertNull(oldRefresh.await())
        advanceUntilIdle()
        assertEquals(newProviders, controller.state.value.providers)
        controller.close()
    }

    @Test
    fun `new generation command does not wait for old cancellation ignoring create`() = runTest {
        val supervisor = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connected("connection-1"))
        val desktop = MutableStateFlow(authenticatedState())
        val gateway = FakeGateway()
        val callbacks = AtomicInteger()
        val controller = controller(gateway, supervisor, desktop) { callbacks.incrementAndGet() }
        advanceUntilIdle()

        gateway.createStarted = CompletableDeferred()
        gateway.createRelease = CompletableDeferred()
        gateway.ignoreCreateCancellation = true
        val oldCreate = async { controller.create(draft()) }
        gateway.createStarted!!.await()

        try {
            gateway.providers = listOf(provider("connection-2"))
            supervisor.value = AgentSupervisorState.Reconnecting(1, 1_000)
            runCurrent()
            supervisor.value = AgentSupervisorState.Connected("connection-2")
            withTimeout(1_000) {
                controller.state.first { it.providers.singleOrNull()?.id == "connection-2" }
            }

            val selection = withTimeout(1_000) { controller.setActive("connection-2", "kimi-k3") }
            assertEquals("connection-2", selection?.providerId)
            assertTrue(controller.state.value.providers.single().active)
            assertEquals(1, callbacks.get())
            assertFalse(oldCreate.isCompleted)
        } finally {
            gateway.createRelease!!.complete(Unit)
        }

        assertNull(oldCreate.await())
        advanceUntilIdle()
        assertEquals("PROVIDER_ACTIVATED", controller.state.value.notice?.code)
        assertEquals(1, callbacks.get())
        controller.close()
    }

    private fun CoroutineScope.controller(
        gateway: FakeGateway,
        supervisor: MutableStateFlow<AgentSupervisorState>,
        desktop: MutableStateFlow<BusinessDesktopState>,
        onChanged: (List<BusinessProvider>) -> Unit = {},
    ) = BusinessProviderSettingsController(gateway, supervisor, desktop, this, onChanged)

    private fun provider(
        id: String,
        active: Boolean = false,
        type: String = "OPENAI_COMPATIBLE",
        authMode: String = "api_key",
    ) = BusinessProvider(
        id = id,
        displayName = id,
        models = listOf(BusinessProviderModel(if (id == "claude") "claude-sonnet" else "kimi-k3", id, active)),
        authMode = authMode,
        hasApiKey = authMode == "api_key",
        active = active,
        type = type,
        model = if (id == "claude") "claude-sonnet" else "kimi-k3",
    )

    private fun draft(apiKey: String? = "sk-test") = BusinessProviderDraft(
        providerId = "relay",
        displayName = "Relay",
        type = "OPENAI_COMPATIBLE",
        baseUrl = "https://relay.example.com/v1",
        model = "kimi-k3",
        apiKey = apiKey,
    )

    private fun authenticatedState() = BusinessDesktopState(
        authenticationStatus = BusinessAuthenticationStatus.AUTHENTICATED,
        identity = BusinessIdentity(
            desktopInstanceId = "desktop-1",
            desktopSessionId = "session-1",
            authSessionId = "auth-1",
            identityEpoch = 1,
            userId = "user-1",
            tenantId = "tenant-1",
            platformId = "platform-1",
            roles = setOf("lawyer"),
            permissions = setOf("provider:write"),
        ),
    )

    private inner class FakeGateway : BusinessConversationGateway {
        override val events: Flow<BusinessAgentEvent> = emptyFlow()
        val calls = mutableListOf<String>()
        var providers = listOf(provider("relay"), provider("claude", type = "ANTHROPIC", authMode = "oauth_cli"))
        var deleteResult = BusinessProviderDeleteResult(true, "relay", "claude")
        var failure: Throwable? = null
        var listStarted: CompletableDeferred<Unit>? = null
        var listRelease: CompletableDeferred<Unit>? = null
        var remainingGatedListCalls: Int? = null
        var ignoreListCancellation = false
        var ungatedListStarted: CompletableDeferred<Unit>? = null
        var createStarted: CompletableDeferred<Unit>? = null
        var createRelease: CompletableDeferred<Unit>? = null
        var deleteStarted: CompletableDeferred<Unit>? = null
        var deleteRelease: CompletableDeferred<Unit>? = null
        var testStarted: CompletableDeferred<Unit>? = null
        var testRelease: CompletableDeferred<Unit>? = null
        var ignoreCreateCancellation = false
        var createCancellationCount = 0
        var closeCount = 0

        override suspend fun listProviders(): List<BusinessProvider> {
            calls += "list"
            val result = providers
            val remaining = remainingGatedListCalls
            val gated = listRelease != null && (remaining == null || remaining > 0)
            if (remaining != null && remaining > 0) remainingGatedListCalls = remaining - 1
            if (gated) {
                listStarted?.complete(Unit)
                try {
                    listRelease?.await()
                } catch (cancelled: CancellationException) {
                    if (!ignoreListCancellation) throw cancelled
                    withContext(NonCancellable) { listRelease?.await() }
                }
            } else {
                ungatedListStarted?.complete(Unit)
            }
            failIfConfigured()
            return result
        }

        override suspend fun createProvider(draft: BusinessProviderDraft): BusinessProvider {
            calls += "create:${draft.providerId}"
            createStarted?.complete(Unit)
            try {
                createRelease?.await()
            } catch (cancelled: CancellationException) {
                createCancellationCount += 1
                if (!ignoreCreateCancellation) throw cancelled
                withContext(NonCancellable) { createRelease?.await() }
            }
            failIfConfigured()
            return provider(draft.providerId)
        }

        override suspend fun updateProvider(draft: BusinessProviderDraft): BusinessProvider {
            calls += "update:${draft.providerId}"
            failIfConfigured()
            return provider(draft.providerId)
        }

        override suspend fun deleteProvider(providerId: String): BusinessProviderDeleteResult {
            calls += "delete:$providerId"
            deleteStarted?.complete(Unit)
            deleteRelease?.await()
            failIfConfigured()
            return deleteResult
        }

        override suspend fun testProvider(providerId: String): BusinessProviderTestResult {
            calls += "test:$providerId"
            testStarted?.complete(Unit)
            testRelease?.await()
            failIfConfigured()
            return BusinessProviderTestResult(true, providerId, "Provider 配置可用")
        }

        override suspend fun providerOAuthStatus(): BusinessProviderOAuthStatus {
            calls += "oauth-status"
            failIfConfigured()
            return BusinessProviderOAuthStatus("ANTHROPIC", "oauth_cli", true, false, "未登录")
        }

        override suspend fun loginProviderOAuth(): BusinessProviderOAuthLoginResult {
            calls += "oauth-login"
            failIfConfigured()
            return BusinessProviderOAuthLoginResult(true, 12345L, "登录已启动")
        }

        override suspend fun setActiveProvider(providerId: String, modelId: String?): BusinessProviderSelection {
            calls += "active:$providerId:$modelId"
            failIfConfigured()
            return BusinessProviderSelection(providerId, requireNotNull(modelId))
        }

        override suspend fun createThread(cwd: String) = BusinessThread("thread-1", "demo", cwd)
        override suspend fun startTurn(
            threadId: String,
            text: String,
            attachments: List<BusinessAttachmentDraft>,
            providerId: String?,
        ) =
            BusinessTurn("turn-1", threadId)
        override suspend fun cancelTurn(turnId: String) = true

        override fun close() {
            closeCount += 1
        }

        private fun failIfConfigured() {
            failure?.let { throw it }
        }
    }
}
