package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.state.BusinessIdentity

/**
 * Provisional Agent registration; the desktop store remains unchanged until [commit].
 *
 * [rollback] must be idempotent and owner-scoped to this transaction. It remains valid after [commit]
 * until the orchestrator publishes READY, so a rejected registry generation can compensate only the
 * provisional identity without deleting a newer authentication operation's workspace.
 */
interface BusinessAgentRegistrationTransaction {
    suspend fun registerIdentity()
    suspend fun registerCapabilityCatalog()
    suspend fun registerInitialContext()
    suspend fun commit()

    /**
     * Publishes the final READY state inside the same ownership barrier used by connection
     * registration. Production transactions override this to reject a connection replacement
     * that happened after the provisional context was sent.
     */
    suspend fun publishReady(publish: () -> Boolean): Boolean = publish()

    suspend fun rollback()
}

/** Task 5 supplies the production coordinator adapter for this deliberately fakeable boundary. */
interface BusinessAgentRegistrationTransactionPort {
    suspend fun prepare(identity: BusinessIdentity): BusinessAgentRegistrationTransaction
    suspend fun publishSignedOut()
    suspend fun clearWorkspace()
}
