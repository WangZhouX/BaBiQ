package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.state.BusinessIdentity

/** Provisional Agent registration; the desktop store remains unchanged until [commit]. */
interface BusinessAgentRegistrationTransaction {
    suspend fun registerIdentity()
    suspend fun registerCapabilityCatalog()
    suspend fun registerInitialContext()
    suspend fun commit()
    suspend fun rollback()
}

/** Task 5 supplies the production coordinator adapter for this deliberately fakeable boundary. */
interface BusinessAgentRegistrationTransactionPort {
    suspend fun prepare(identity: BusinessIdentity): BusinessAgentRegistrationTransaction
    suspend fun publishSignedOut()
    suspend fun clearWorkspace()
}
