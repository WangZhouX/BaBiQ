package com.wzx.huitai.desktop.controller

import com.wzx.huitai.agent.client.AgentConnectionSupervisor
import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.desktop.state.BusinessConnectionStatus
import com.wzx.huitai.desktop.state.BusinessDesktopEvent
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessDesktopStore
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.presentation.context.PageContextSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface BusinessConnectionLifecycle {
    val state: StateFlow<AgentSupervisorState>
    suspend fun start()
    suspend fun manualRetry(): Boolean
    suspend fun shutdown()
}

class AgentConnectionLifecycleAdapter(
    private val supervisor: AgentConnectionSupervisor,
) : BusinessConnectionLifecycle {
    override val state: StateFlow<AgentSupervisorState> = supervisor.state
    override suspend fun start() = supervisor.start()
    override suspend fun manualRetry(): Boolean = supervisor.manualRetry()
    override suspend fun shutdown() = supervisor.shutdown()
}

interface BusinessRegistrationPort {
    suspend fun bindIdentity(identity: BusinessIdentity)
    suspend fun registerCatalog(identity: BusinessIdentity, catalogEpoch: Long)
}

/** 仅编排 connection/identity 生命周期，不接管 chat 或 workspace 的内部逻辑。 */
class BusinessDesktopCoordinator(
    private val store: BusinessDesktopStore,
    private val connection: BusinessConnectionLifecycle,
    private val registration: BusinessRegistrationPort,
    private val workspace: BusinessWorkspaceController,
    private val scope: CoroutineScope,
) {
    private val lifecycleMutex = Mutex()
    private val identityMutex = Mutex()
    private var observer: Job? = null
    private var shutdown = false

    val state: StateFlow<BusinessDesktopState> = store.state

    suspend fun start() {
        lifecycleMutex.withLock {
            if (shutdown || observer?.isActive == true) return
            observer = scope.launch {
                connection.state.collect { supervisorState ->
                    store.dispatch(BusinessDesktopEvent.ConnectionChanged(supervisorState.toBusinessStatus()))
                }
            }
        }
        connection.start()
    }

    suspend fun onAuthenticated(
        identity: BusinessIdentity,
        catalogEpoch: Long,
        initialPage: PageContextSnapshot,
    ) = identityMutex.withLock {
        check(!shutdown) { "Business desktop is shut down" }
        registration.bindIdentity(identity)
        registration.registerCatalog(identity, catalogEpoch)
        store.dispatch(BusinessDesktopEvent.IdentityAuthenticated(identity))
        workspace.activateIdentity(identity, catalogEpoch, initialPage)
    }

    suspend fun onMembershipExpired() = identityMutex.withLock {
        workspace.clearIdentity()
        store.dispatch(BusinessDesktopEvent.MembershipExpired)
    }

    suspend fun onAuthenticationExpired() = identityMutex.withLock {
        workspace.clearIdentity()
        store.dispatch(BusinessDesktopEvent.AuthenticationExpired)
    }

    suspend fun signOut() = identityMutex.withLock {
        workspace.clearIdentity()
        store.dispatch(BusinessDesktopEvent.SignedOut)
    }

    suspend fun manualRetry(): Boolean = connection.manualRetry()

    suspend fun shutdown() {
        val activeObserver = lifecycleMutex.withLock {
            if (shutdown) return
            shutdown = true
            observer.also { observer = null }
        }
        activeObserver?.cancel()
        identityMutex.withLock { workspace.clearIdentity() }
        connection.shutdown()
        store.dispatch(BusinessDesktopEvent.ConnectionChanged(BusinessConnectionStatus.SHUTDOWN))
    }
}

private fun AgentSupervisorState.toBusinessStatus(): BusinessConnectionStatus = when (this) {
    AgentSupervisorState.Idle -> BusinessConnectionStatus.DISCONNECTED
    AgentSupervisorState.Connecting -> BusinessConnectionStatus.CONNECTING
    is AgentSupervisorState.Connected -> BusinessConnectionStatus.CONNECTED
    is AgentSupervisorState.Reconnecting -> BusinessConnectionStatus.RECONNECTING
    AgentSupervisorState.ManualRetryRequired -> BusinessConnectionStatus.MANUAL_RETRY_REQUIRED
    AgentSupervisorState.AuthenticationFailed -> BusinessConnectionStatus.AUTHENTICATION_FAILED
    AgentSupervisorState.Shutdown -> BusinessConnectionStatus.SHUTDOWN
}
