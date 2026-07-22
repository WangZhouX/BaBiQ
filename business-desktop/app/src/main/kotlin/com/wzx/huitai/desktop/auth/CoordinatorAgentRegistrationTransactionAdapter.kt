package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.controller.BusinessDesktopCoordinator
import com.wzx.huitai.desktop.controller.ProvisionalBusinessRegistrationTransaction
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.presentation.context.PageContextSnapshot
import kotlinx.coroutines.sync.Mutex

/** Bridges authentication's two-phase contract to the desktop coordinator's owner-scoped transaction. */
class CoordinatorAgentRegistrationTransactionAdapter(
    private val coordinator: BusinessDesktopCoordinator,
    private val watermarks: () -> BusinessRegistrationWatermarks,
    private val initialPage: (BusinessIdentity) -> PageContextSnapshot,
    private val currentConnectionId: (() -> String)? = null,
    private val readyPublicationMutex: Mutex? = null,
) : BusinessAgentRegistrationTransactionPort {
    init {
        require((currentConnectionId == null) == (readyPublicationMutex == null)) {
            "connection ownership and READY publication mutex must be configured together"
        }
    }

    constructor(
        coordinator: BusinessDesktopCoordinator,
        catalogEpoch: Long,
        initialPage: (BusinessIdentity) -> PageContextSnapshot,
    ) : this(
        coordinator,
        { BusinessRegistrationWatermarks(catalogEpoch, 1) },
        initialPage,
    )

    override suspend fun prepare(identity: BusinessIdentity): BusinessAgentRegistrationTransaction {
        val preparedConnectionId = currentConnectionId?.invoke()
        val watermarks = watermarks()
        val delegate = coordinator.prepareRegistration(
            identity,
            watermarks.catalogEpoch,
            initialPage(identity),
            watermarks.contextSequence,
        )
        return Transaction(delegate, preparedConnectionId, readyPublicationMutex?.let { Any() })
    }

    override suspend fun publishSignedOut() = coordinator.publishSignedOutRegistration()

    override suspend fun clearWorkspace() = coordinator.clearWorkspace()

    private inner class Transaction(
        private val delegate: ProvisionalBusinessRegistrationTransaction,
        private val preparedConnectionId: String?,
        private val publicationOwner: Any?,
    ) : BusinessAgentRegistrationTransaction {
        private var publicationAcquired = false
        private var publicationReleased = false

        override suspend fun registerIdentity() = withConnectionOwnership { delegate.registerIdentity() }
        override suspend fun registerCapabilityCatalog() = withConnectionOwnership {
            delegate.registerCapabilityCatalog()
        }
        override suspend fun registerInitialContext() {
            withConnectionOwnership { delegate.registerInitialContext() }
            acquirePublicationBarrier()
            checkConnectionOwnership()
        }
        override suspend fun commit() = withConnectionOwnership { delegate.commit() }
        override suspend fun publishReady(publish: () -> Boolean): Boolean {
            checkConnectionOwnership()
            return publish().also { published ->
                if (published) releasePublicationBarrier()
            }
        }
        override suspend fun rollback() {
            try {
                delegate.rollback()
            } finally {
                releasePublicationBarrier()
            }
        }

        private suspend fun withConnectionOwnership(block: suspend () -> Unit) {
            checkConnectionOwnership()
            block()
            checkConnectionOwnership()
        }

        private fun checkConnectionOwnership() {
            preparedConnectionId ?: return
            check(currentConnectionId?.invoke() == preparedConnectionId) {
                "Agent connection changed during authentication registration"
            }
        }

        private fun releasePublicationBarrier() {
            val owner = publicationOwner ?: return
            if (publicationAcquired && !publicationReleased) {
                publicationReleased = true
                readyPublicationMutex?.unlock(owner)
            }
        }

        private suspend fun acquirePublicationBarrier() {
            val owner = publicationOwner ?: return
            check(!publicationReleased) { "READY publication barrier is already released" }
            if (!publicationAcquired) {
                readyPublicationMutex?.lock(owner)
                publicationAcquired = true
            }
        }
    }
}

data class BusinessRegistrationWatermarks(
    val catalogEpoch: Long,
    val contextSequence: Long,
) {
    init {
        require(catalogEpoch > 0) { "catalogEpoch must be positive" }
        require(contextSequence > 0) { "contextSequence must be positive" }
    }
}
