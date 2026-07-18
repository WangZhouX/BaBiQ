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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface BusinessConnectionLifecycle {
    val state: StateFlow<AgentSupervisorState>
    suspend fun start()
    suspend fun manualRetry(): Boolean
    suspend fun reconnect(expectedConnectionId: String): Boolean = false
    suspend fun shutdown()
}

class AgentConnectionLifecycleAdapter(
    private val supervisor: AgentConnectionSupervisor,
) : BusinessConnectionLifecycle {
    override val state: StateFlow<AgentSupervisorState> = supervisor.state
    override suspend fun start() = supervisor.start()
    override suspend fun manualRetry(): Boolean = supervisor.manualRetry()
    override suspend fun reconnect(expectedConnectionId: String): Boolean =
        supervisor.requestReconnect(expectedConnectionId)
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
    private val registrationMutex = Mutex()
    private var observer: Job? = null
    private var shutdown = false
    private var identityGeneration: Long = 0

    val state: StateFlow<BusinessDesktopState> = store.state

    suspend fun start() {
        lifecycleMutex.withLock {
            if (shutdown || observer?.isActive == true) return
            observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                connection.state.collect { supervisorState ->
                    lifecycleMutex.withLock {
                        if (!shutdown) {
                            store.dispatch(BusinessDesktopEvent.ConnectionChanged(supervisorState.toBusinessStatus()))
                        }
                    }
                }
            }
        }
        try {
            connection.start()
        } finally {
            if (lifecycleMutex.withLock { shutdown }) connection.shutdown()
        }
    }

    suspend fun onAuthenticated(
        identity: BusinessIdentity,
        catalogEpoch: Long,
        initialPage: PageContextSnapshot,
    ) = registrationMutex.withLock {
        val generation = lifecycleMutex.withLock {
            check(!shutdown) { "Business desktop is shut down" }
            ++identityGeneration
        }
        registration.bindIdentity(identity)
        if (!isCurrentIdentityGeneration(generation)) return@withLock
        registration.registerCatalog(identity, catalogEpoch)
        val committed = lifecycleMutex.withLock {
            if (shutdown || generation != identityGeneration) return@withLock false
            store.dispatch(BusinessDesktopEvent.IdentityAuthenticated(identity))
            true
        }
        if (!committed) return@withLock
        workspace.activateIdentity(identity, catalogEpoch, initialPage, generation)
    }

    suspend fun onMembershipExpired() = invalidateIdentity(BusinessDesktopEvent.MembershipExpired)

    suspend fun onAuthenticationExpired() = invalidateIdentity(BusinessDesktopEvent.AuthenticationExpired)

    suspend fun signOut() = invalidateIdentity(BusinessDesktopEvent.SignedOut)

    suspend fun manualRetry(): Boolean {
        if (lifecycleMutex.withLock { shutdown }) return false
        var accepted = false
        try {
            accepted = connection.manualRetry()
        } finally {
            if (lifecycleMutex.withLock { shutdown }) connection.shutdown()
        }
        return accepted && !lifecycleMutex.withLock { shutdown }
    }

    suspend fun shutdown() {
        val resources = lifecycleMutex.withLock {
            if (shutdown) return
            shutdown = true
            identityGeneration += 1
            store.dispatch(BusinessDesktopEvent.ConnectionChanged(BusinessConnectionStatus.SHUTDOWN))
            ShutdownResources(observer, identityGeneration).also { observer = null }
        }
        resources.observer?.cancel()
        workspace.clearIdentity(resources.identityGeneration)
        connection.shutdown()
    }

    private suspend fun invalidateIdentity(event: BusinessDesktopEvent) {
        val generation = lifecycleMutex.withLock {
            if (shutdown) return
            identityGeneration += 1
            store.dispatch(event)
            identityGeneration
        }
        workspace.clearIdentity(generation)
    }

    private suspend fun isCurrentIdentityGeneration(generation: Long): Boolean = lifecycleMutex.withLock {
        !shutdown && generation == identityGeneration
    }

    private data class ShutdownResources(
        val observer: Job?,
        val identityGeneration: Long,
    )
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
