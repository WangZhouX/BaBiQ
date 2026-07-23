package com.wzx.huitai.agent.application

/**
 * A point-in-time authenticated identity captured before an application action is accepted.
 *
 * [generation] is owned by the caller's authentication registry. It lets the gate reject a
 * snapshot that became stale even when a later login happens to use the same identity fields.
 */
data class ApplicationAuthenticationSnapshot(
    val identity: TrustedApplicationIdentity,
    val generation: Long,
)

/** Authentication boundary for inbound application actions. */
interface ApplicationAuthenticationGate {
    /** Returns a full trusted identity only while authenticated business use is ready. */
    fun captureIfReady(): ApplicationAuthenticationSnapshot?

    /** Revalidates that [snapshot] still belongs to the currently published identity. */
    fun isCurrent(snapshot: ApplicationAuthenticationSnapshot): Boolean

    /** Executes action admission only while [snapshot] owns a revocation-visible permit. */
    suspend fun <T> withCurrentPermit(
        snapshot: ApplicationAuthenticationSnapshot,
        use: suspend () -> T,
    ): T? = if (isCurrent(snapshot)) use() else null

    companion object {
        /** Compatibility adapter for callers without an external authentication lifecycle. */
        fun trustedBy(identity: () -> TrustedApplicationIdentity): ApplicationAuthenticationGate =
            object : ApplicationAuthenticationGate {
                override fun captureIfReady() = ApplicationAuthenticationSnapshot(identity(), 0L)

                override fun isCurrent(snapshot: ApplicationAuthenticationSnapshot) = true

                override suspend fun <T> withCurrentPermit(
                    snapshot: ApplicationAuthenticationSnapshot,
                    use: suspend () -> T,
                ): T = use()
            }
    }
}
