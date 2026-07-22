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
    suspend fun publishSignedOut() = Unit
}

interface ProvisionalBusinessRegistrationTransaction {
    suspend fun registerIdentity()
    suspend fun registerCapabilityCatalog()
    suspend fun registerInitialContext()
    suspend fun commit()
    suspend fun rollback()
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
        initialContextSequence: Long = 1,
    ) {
        val transaction = prepareRegistration(identity, catalogEpoch, initialPage, initialContextSequence)
        try {
            transaction.registerIdentity()
            transaction.registerCapabilityCatalog()
            transaction.registerInitialContext()
            transaction.commit()
        } catch (_: RegistrationSupersededException) {
            transaction.rollback()
        } catch (failure: Throwable) {
            transaction.rollback()
            throw failure
        }
    }

    suspend fun prepareRegistration(
        identity: BusinessIdentity,
        catalogEpoch: Long,
        initialPage: PageContextSnapshot,
        initialContextSequence: Long = 1,
    ): ProvisionalBusinessRegistrationTransaction {
        require(catalogEpoch > 0) { "catalogEpoch must be positive" }
        require(initialContextSequence > 0) { "initialContextSequence must be positive" }
        val owner = Any()
        registrationMutex.lock(owner)
        return try {
            val generation = lifecycleMutex.withLock {
                check(!shutdown) { "Business desktop is shut down" }
                ++identityGeneration
            }
            CoordinatorRegistrationTransaction(
                owner,
                generation,
                identity,
                catalogEpoch,
                initialPage,
                initialContextSequence,
            )
        } catch (failure: Throwable) {
            registrationMutex.unlock(owner)
            throw failure
        }
    }

    suspend fun publishSignedOutRegistration() = registrationMutex.withLock {
        registration.publishSignedOut()
    }

    suspend fun clearWorkspace() {
        val generation = lifecycleMutex.withLock {
            if (shutdown) return
            ++identityGeneration
        }
        workspace.clearIdentity(generation)
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
        workspace.clearIdentity(resources.identityGeneration, clearEvent = null)
        connection.shutdown()
    }

    private suspend fun invalidateIdentity(event: BusinessDesktopEvent) {
        val generation = lifecycleMutex.withLock {
            if (shutdown) return
            identityGeneration += 1
            store.dispatch(event)
            identityGeneration
        }
        workspace.clearIdentity(generation, clearEvent = null)
    }

    private suspend fun isCurrentIdentityGeneration(generation: Long): Boolean = lifecycleMutex.withLock {
        !shutdown && generation == identityGeneration
    }

    private inner class CoordinatorRegistrationTransaction(
        private val owner: Any,
        private val generation: Long,
        private val identity: BusinessIdentity,
        private val catalogEpoch: Long,
        private val initialPage: PageContextSnapshot,
        private val initialContextSequence: Long,
    ) : ProvisionalBusinessRegistrationTransaction {
        private val transactionMutex = Mutex()
        private var stage = RegistrationStage.PREPARED
        private var lockReleased = false

        override suspend fun registerIdentity() = transactionMutex.withLock {
            check(stage == RegistrationStage.PREPARED) { "identity registration is out of order" }
            checkCurrent()
            registration.bindIdentity(identity)
            checkCurrent()
            stage = RegistrationStage.IDENTITY
        }

        override suspend fun registerCapabilityCatalog() = transactionMutex.withLock {
            check(stage == RegistrationStage.IDENTITY) { "catalog registration is out of order" }
            checkCurrent()
            registration.registerCatalog(identity, catalogEpoch)
            checkCurrent()
            stage = RegistrationStage.CATALOG
        }

        override suspend fun registerInitialContext() = transactionMutex.withLock {
            check(stage == RegistrationStage.CATALOG) { "context registration is out of order" }
            checkCurrent()
            workspace.publishProvisionalPage(identity, catalogEpoch, initialPage, initialContextSequence)
            checkCurrent()
            stage = RegistrationStage.CONTEXT
        }

        override suspend fun commit() = transactionMutex.withLock {
            check(stage == RegistrationStage.CONTEXT) { "registration commit is out of order" }
            checkCurrent()
            check(
                workspace.attachPublishedIdentity(
                    identity = identity,
                    catalogEpoch = catalogEpoch,
                    snapshot = initialPage,
                    lifecycleGeneration = generation,
                    publishedContextSequence = initialContextSequence,
                ),
            ) { "registration ownership changed before commit" }
            stage = RegistrationStage.COMMITTED
            releaseRegistrationLock()
        }

        override suspend fun rollback() = transactionMutex.withLock {
            if (stage == RegistrationStage.ROLLED_BACK) return@withLock
            try {
                if (isCurrentIdentityGeneration(generation)) {
                    registration.publishSignedOut()
                    workspace.clearIdentity(generation)
                }
                stage = RegistrationStage.ROLLED_BACK
            } finally {
                releaseRegistrationLock()
            }
        }

        private suspend fun checkCurrent() {
            if (!isCurrentIdentityGeneration(generation)) throw RegistrationSupersededException()
        }

        private fun releaseRegistrationLock() {
            if (!lockReleased) {
                lockReleased = true
                registrationMutex.unlock(owner)
            }
        }
    }

    private enum class RegistrationStage {
        PREPARED,
        IDENTITY,
        CATALOG,
        CONTEXT,
        COMMITTED,
        ROLLED_BACK,
    }

    private class RegistrationSupersededException : IllegalStateException("registration ownership changed")

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
