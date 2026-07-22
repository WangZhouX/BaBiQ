package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.controller.BusinessDesktopCoordinator
import com.wzx.huitai.desktop.controller.ProvisionalBusinessRegistrationTransaction
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.presentation.context.PageContextSnapshot

/** Bridges authentication's two-phase contract to the desktop coordinator's owner-scoped transaction. */
class CoordinatorAgentRegistrationTransactionAdapter(
    private val coordinator: BusinessDesktopCoordinator,
    private val catalogEpoch: Long,
    private val initialPage: (BusinessIdentity) -> PageContextSnapshot,
) : BusinessAgentRegistrationTransactionPort {
    override suspend fun prepare(identity: BusinessIdentity): BusinessAgentRegistrationTransaction {
        val delegate = coordinator.prepareRegistration(identity, catalogEpoch, initialPage(identity))
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
