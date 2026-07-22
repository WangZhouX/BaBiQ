package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.controller.BusinessDesktopCoordinator
import com.wzx.huitai.desktop.controller.ProvisionalBusinessRegistrationTransaction
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.presentation.context.PageContextSnapshot

/** Bridges authentication's two-phase contract to the desktop coordinator's owner-scoped transaction. */
class CoordinatorAgentRegistrationTransactionAdapter(
    private val coordinator: BusinessDesktopCoordinator,
    private val watermarks: () -> BusinessRegistrationWatermarks,
    private val initialPage: (BusinessIdentity) -> PageContextSnapshot,
) : BusinessAgentRegistrationTransactionPort {
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
        val watermarks = watermarks()
        val delegate = coordinator.prepareRegistration(
            identity,
            watermarks.catalogEpoch,
            initialPage(identity),
            watermarks.contextSequence,
        )
        return Transaction(delegate)
    }

    override suspend fun publishSignedOut() = coordinator.publishSignedOutRegistration()

    override suspend fun clearWorkspace() = coordinator.clearWorkspace()

    private class Transaction(
        private val delegate: ProvisionalBusinessRegistrationTransaction,
    ) : BusinessAgentRegistrationTransaction {
        override suspend fun registerIdentity() = delegate.registerIdentity()
        override suspend fun registerCapabilityCatalog() = delegate.registerCapabilityCatalog()
        override suspend fun registerInitialContext() = delegate.registerInitialContext()
        override suspend fun commit() = delegate.commit()
        override suspend fun rollback() = delegate.rollback()
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
