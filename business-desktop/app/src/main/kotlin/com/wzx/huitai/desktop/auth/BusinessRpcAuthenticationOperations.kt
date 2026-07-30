package com.wzx.huitai.desktop.auth

import com.wzx.huitai.agent.business.BusinessRpcException
import com.wzx.huitai.agent.business.auth.BusinessAuthClient
import com.wzx.huitai.agent.business.auth.BusinessAuthStateChangeCode
import com.wzx.huitai.agent.business.auth.BusinessAuthStateChanged
import com.wzx.huitai.agent.business.auth.BusinessAuthStatus
import com.wzx.huitai.agent.business.auth.BusinessSessionView
import com.wzx.huitai.agent.business.auth.BusinessTenantCandidate as RpcBusinessTenantCandidate
import com.wzx.huitai.desktop.state.BusinessIdentity
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Bridges the existing login UI contract to the server-owned OA authentication RPC. */
class BusinessRpcAuthenticationOperations(
    private val client: BusinessAuthClient,
    private val identityRegistry: BusinessIdentityRegistry,
    private val desktopInstanceId: String,
    private val desktopSessionId: String,
    private val platformId: Int,
    private val onReady: suspend (BusinessIdentity) -> Unit = {},
    private val onSignedOut: suspend () -> Unit = {},
    private val onAuthenticationExpiredState: suspend () -> Unit = onSignedOut,
    private val onMembershipExpiredState: suspend () -> Unit = onSignedOut,
    private val onRecovering: suspend () -> Unit = {},
    private val currentConnectionId: () -> String? = { null },
) : BusinessAuthenticationOperations, BusinessAuthenticationLifecycleOperations {
    private val candidates = ConcurrentHashMap<String, IssuedCandidate>()
    private val mutableLastError = MutableStateFlow<BusinessLoginMessage?>(null)
    private val attemptLock = Any()
    private var nextAttemptOrdinal = 0L
    private var activeAttempt: AuthenticationAttempt? = null
    private var preparedStartupSession: PreparedStartupSession? = null

    override val gate: StateFlow<BusinessAccessGateState> = identityRegistry.gate
    val lastError: StateFlow<BusinessLoginMessage?> = mutableLastError.asStateFlow()

    suspend fun prepareStartup() {
        synchronized(attemptLock) {
            check(preparedStartupSession == null) { "startup authentication protocol is already prepared" }
        }

        while (true) {
            val connectionId = checkNotNull(currentConnectionId()) {
                "agent connection is not available for startup authentication"
            }
            val expectedSnapshot = identityRegistry.snapshot.value
            val session = client.session()
            val prepared = synchronized(attemptLock) {
                check(preparedStartupSession == null) { "startup authentication protocol is already prepared" }
                if (
                    currentConnectionId() != connectionId ||
                    identityRegistry.snapshot.value != expectedSnapshot
                ) {
                    false
                } else {
                    preparedStartupSession = PreparedStartupSession(connectionId, expectedSnapshot, session)
                    true
                }
            }
            if (prepared) return
        }
    }

    override suspend fun findTenantCandidates(account: String): List<BusinessTenantCandidate> {
        val attempt = beginAttempt(BusinessAccessGateState.VERIFYING, clearCandidates = true)
        return try {
            val values = client.tenantCandidates(account).map { candidate ->
                // The UI gets an opaque selection object; user/tenant identifiers remain server-owned.
                BusinessTenantCandidate(
                    candidateId = candidate.candidateId,
                    name = candidate.name,
                    platformId = candidate.platformId.takeIf { it > 0 } ?: platformId,
                    tenantEnterStatus = tenantEnterStatus(candidate),
                )
            }
            val committed = synchronized(attemptLock) {
                if (!isAttemptCurrentLocked(attempt)) return@synchronized false
                candidates.clear()
                values.forEach { value -> candidates[value.candidateId] = IssuedCandidate(account, value) }
                true
            }
            if (!committed) throw CancellationException("Tenant candidate lookup superseded")
            values
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            clearSignedOut(attempt)
            throw cancelled
        } catch (failure: Throwable) {
            val current = clearSignedOut(attempt)
            if (!current) throw CancellationException("Tenant candidate lookup superseded")
            throw mapFailure(failure)
        }
    }

    override fun enterTenantSelection() = identityRegistry.transitionTo(BusinessAccessGateState.SELECTING_TENANT)

    override fun cancelTenantSelection() {
        synchronized(attemptLock) {
            when (identityRegistry.gate.value) {
                BusinessAccessGateState.VERIFYING,
                BusinessAccessGateState.AUTHENTICATING,
                BusinessAccessGateState.SELECTING_TENANT,
                -> {
                    activeAttempt = null
                    candidates.clear()
                    identityRegistry.invalidate(BusinessAccessGateState.SIGNED_OUT)
                }
                else -> Unit
            }
        }
    }

    override suspend fun authenticate(account: String, password: CharArray, candidate: BusinessTenantCandidate) {
        val attempt = beginAttempt(BusinessAccessGateState.AUTHENTICATING)
        try {
            val issued = candidates[candidate.candidateId]
            check(issued != null && issued.account == account && issued.candidate == candidate) {
                "candidate is not issued for this login"
            }
            check(candidate.tenantEnterStatus != 1 && candidate.tenantEnterStatus != 2) {
                "candidate is not available"
            }
            val session = client.login(account, password, candidate.candidateId)
            publishReady(session, attempt)
        } catch (cancelled: CancellationException) {
            clearSignedOut(attempt)
            throw cancelled
        } catch (failure: Throwable) {
            val current = clearSignedOut(attempt)
            if (!current) throw CancellationException("Authentication attempt superseded")
            throw if (failure is IllegalStateException && failure.message == "candidate is not issued for this login") {
                BusinessAuthenticationException(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR)
            } else if (failure is IllegalStateException && failure.message == "candidate is not available") {
                BusinessAuthenticationException(BusinessLoginErrorCode.TENANT_UNAVAILABLE)
            } else if (failure is BusinessAuthenticationException) {
                failure
            } else {
                mapFailure(failure)
            }
        } finally {
            // Keep the adapter safe even when a stale/forged candidate is rejected locally.
            Arrays.fill(password, '\u0000')
        }
    }

    override suspend fun restore() {
        // The lifecycle observer is asynchronous. If a user starts an interactive login as the
        // first Connected emission is being delivered, that newer intent owns the gate; a late
        // startup restore must not replace its attempt and discard the returned candidate ticket.
        val startup = beginRestoreAttempt() ?: return
        val attempt = startup.attempt
        try {
            // The session probe is authoritative. Calling restore blindly would ask the server
            // to transition a SIGNED_OUT session and can turn a harmless startup into an error.
            val current = startup.session ?: client.session()
            val session = when (current.status) {
                BusinessAuthStatus.READY -> current
                BusinessAuthStatus.DETACHED -> client.restore()
                else -> {
                    clearSignedOut(attempt)
                    return
                }
            }
            if (session.status == BusinessAuthStatus.READY) publishReady(session, attempt) else clearSignedOut(attempt)
        } catch (cancelled: CancellationException) {
            clearSignedOut(attempt)
            throw cancelled
        } catch (failure: Throwable) {
            val current = clearSignedOut(attempt)
            if (!current) throw CancellationException("Authentication attempt superseded")
            throw if (failure is BusinessAuthenticationException) failure else mapFailure(failure)
        }
    }

    override suspend fun attachAfterReconnect() {
        val attempt = beginAttempt(BusinessAccessGateState.RESTORING)
        try {
            val current = client.session()
            val session = when (current.status) {
                BusinessAuthStatus.READY -> current
                BusinessAuthStatus.DETACHED -> {
                    val attachHandle = current.attachHandle?.takeIf(String::isNotBlank)
                        ?: throw BusinessAuthenticationException(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR)
                    client.attach(attachHandle)
                }
                else -> {
                    clearSignedOut(attempt)
                    return
                }
            }
            if (session.status == BusinessAuthStatus.READY) publishReady(session, attempt) else clearSignedOut(attempt)
        } catch (cancelled: CancellationException) {
            clearSignedOut(attempt)
            throw cancelled
        } catch (failure: Throwable) {
            val current = clearSignedOut(attempt)
            if (!current) throw CancellationException("Authentication attempt superseded")
            throw if (failure is BusinessAuthenticationException) failure else mapFailure(failure)
        }
    }

    override suspend fun onConnectionUnavailable() {
        val invalidated = synchronized(attemptLock) {
            val gate = identityRegistry.gate.value
            val shouldInvalidate = activeAttempt != null ||
                (gate != BusinessAccessGateState.STARTING && gate != BusinessAccessGateState.SIGNED_OUT)
            activeAttempt = null
            preparedStartupSession = null
            candidates.clear()
            if (shouldInvalidate) identityRegistry.invalidate(BusinessAccessGateState.RESTORING)
            shouldInvalidate
        }
        if (invalidated) onRecovering()
    }

    suspend fun reconcileAuthStateChanged(change: BusinessAuthStateChanged) {
        val expectedSnapshot = identityRegistry.snapshot.value
        val session = client.session()

        if (session.status == BusinessAuthStatus.READY && session.generation > change.generation) {
            val identity = readyIdentity(session)
            val published = synchronized(attemptLock) {
                if (!identityRegistry.publishReadyIfCurrent(identity, expectedSnapshot)) {
                    false
                } else {
                    activeAttempt = null
                    preparedStartupSession = null
                    candidates.clear()
                    true
                }
            }
            if (published) onReady(identity)
            return
        }

        if (
            change.state != BusinessAuthStatus.SIGNED_OUT ||
            session.status != change.state ||
            session.authSessionId != change.authSessionId ||
            session.generation != change.generation
        ) return

        val errorCode = when (change.businessCode) {
            BusinessAuthStateChangeCode.AUTH_EXPIRED -> BusinessLoginErrorCode.AUTH_EXPIRED
            BusinessAuthStateChangeCode.MEMBERSHIP_EXPIRED -> BusinessLoginErrorCode.MEMBERSHIP_EXPIRED
        }
        val invalidated = synchronized(attemptLock) {
            if (!identityRegistry.invalidateIfCurrent(expectedSnapshot, BusinessAccessGateState.SIGNED_OUT)) {
                false
            } else {
                activeAttempt = null
                preparedStartupSession = null
                candidates.clear()
                true
            }
        }
        if (!invalidated) return
        mutableLastError.value = BusinessLoginMessage(errorCode)
        withContext(NonCancellable) {
            when (change.businessCode) {
                BusinessAuthStateChangeCode.AUTH_EXPIRED -> onAuthenticationExpiredState()
                BusinessAuthStateChangeCode.MEMBERSHIP_EXPIRED -> onMembershipExpiredState()
            }
        }
    }

    suspend fun logout() {
        synchronized(attemptLock) {
            activeAttempt = null
            identityRegistry.invalidate(BusinessAccessGateState.SIGNED_OUT)
            candidates.clear()
        }
        onSignedOut()
        try {
            client.logout()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        }
    }

    suspend fun onAuthenticationExpired() {
        mutableLastError.value = BusinessLoginMessage(BusinessLoginErrorCode.AUTH_EXPIRED)
        logout()
    }

    suspend fun onMembershipExpired() {
        mutableLastError.value = BusinessLoginMessage(BusinessLoginErrorCode.MEMBERSHIP_EXPIRED)
        logout()
    }

    override suspend fun onLocalCredentialStoreFailure(code: BusinessLoginErrorCode) = logout()

    override suspend fun close() {
        synchronized(attemptLock) {
            activeAttempt = null
            preparedStartupSession = null
            candidates.clear()
            identityRegistry.invalidate(BusinessAccessGateState.SIGNED_OUT)
        }
    }

    private suspend fun publishReady(session: BusinessSessionView, attempt: AuthenticationAttempt) {
        val identity = readyIdentity(session)
        val published = synchronized(attemptLock) {
            isAttemptCurrentLocked(attempt) && identityRegistry.publishReady(identity, attempt.generation)
        }
        if (!published) throw CancellationException("Authentication attempt superseded")
        onReady(identity)
        synchronized(attemptLock) {
            if (isAttemptCurrentLocked(attempt)) {
                activeAttempt = null
                candidates.clear()
            }
        }
    }

    private fun readyIdentity(session: BusinessSessionView): BusinessIdentity {
        if (session.status != BusinessAuthStatus.READY || session.identityEpoch <= 0) {
            throw BusinessAuthenticationException(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR)
        }
        val user = session.user
            ?: throw BusinessAuthenticationException(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR)
        val tenant = session.tenant
            ?: throw BusinessAuthenticationException(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR)
        val authSessionId = session.authSessionId
            ?: throw BusinessAuthenticationException(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR)
        val serverPlatformId = session.platformId?.takeIf(String::isNotBlank)
            ?: throw BusinessAuthenticationException(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR)
        val identity = BusinessIdentity(
            desktopInstanceId = desktopInstanceId,
            desktopSessionId = desktopSessionId,
            authSessionId = authSessionId,
            identityEpoch = session.identityEpoch,
            userId = user.id,
            tenantId = tenant.id,
            platformId = serverPlatformId,
            roles = session.roles,
            permissions = session.permissions,
        )
        return identity
    }

    private suspend fun clearSignedOut(attempt: AuthenticationAttempt): Boolean {
        val cleared = synchronized(attemptLock) {
            if (!isAttemptCurrentLocked(attempt)) return@synchronized false
            activeAttempt = null
            candidates.clear()
            identityRegistry.invalidate(BusinessAccessGateState.SIGNED_OUT)
            true
        }
        if (cleared) withContext(NonCancellable) { onSignedOut() }
        return cleared
    }

    private fun beginAttempt(
        targetGate: BusinessAccessGateState,
        clearCandidates: Boolean = false,
    ): AuthenticationAttempt = synchronized(attemptLock) {
        beginAttemptLocked(targetGate, clearCandidates)
    }

    private fun beginRestoreAttempt(): StartupRestoreAttempt? = synchronized(attemptLock) {
        if (activeAttempt != null) {
            preparedStartupSession = null
            return@synchronized null
        }
        val connectionId = currentConnectionId()
        val currentSnapshot = identityRegistry.snapshot.value
        val session = preparedStartupSession.also { preparedStartupSession = null }
            ?.takeIf { prepared ->
                prepared.connectionId == connectionId && prepared.expectedSnapshot == currentSnapshot
            }
            ?.session
        StartupRestoreAttempt(
            attempt = beginAttemptLocked(BusinessAccessGateState.RESTORING),
            session = session,
        )
    }

    private fun beginAttemptLocked(
        targetGate: BusinessAccessGateState,
        clearCandidates: Boolean = false,
    ): AuthenticationAttempt {
        if (clearCandidates) candidates.clear()
        val attempt = AuthenticationAttempt(
            ordinal = ++nextAttemptOrdinal,
            generation = identityRegistry.currentGeneration(),
        )
        activeAttempt = attempt
        identityRegistry.transitionTo(targetGate)
        return attempt
    }

    private fun isAttemptCurrentLocked(attempt: AuthenticationAttempt): Boolean =
        activeAttempt == attempt && identityRegistry.currentGeneration() == attempt.generation

    private fun mapFailure(failure: Throwable): BusinessAuthenticationException {
        val code = (failure as? BusinessRpcException)?.businessCode.orEmpty()
        val mapped = when {
            code.contains("INVALID_PASSWORD", true) -> BusinessLoginErrorCode.INVALID_PASSWORD_FORMAT
            code.contains("INVALID_CREDENTIAL", true) -> BusinessLoginErrorCode.INVALID_CREDENTIALS
            code.contains("ACCOUNT_NOT_FOUND", true) -> BusinessLoginErrorCode.ACCOUNT_NOT_FOUND
            code.contains("TIMEOUT", true) -> BusinessLoginErrorCode.REMOTE_TIMEOUT
            code.contains("PERMISSION", true) -> BusinessLoginErrorCode.PERMISSION_LOAD_FAILED
            failure is SerializationException -> BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR
            code.contains("PROTOCOL", true) || (failure is BusinessRpcException && failure.remoteCode == -32041) -> BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR
            else -> BusinessLoginErrorCode.REMOTE_UNAVAILABLE
        }
        mutableLastError.value = BusinessLoginMessage(mapped)
        return BusinessAuthenticationException(mapped)
    }

    private fun tenantEnterStatus(candidate: RpcBusinessTenantCandidate): Int = when {
        candidate.tenantEnterStatus != 0 -> candidate.tenantEnterStatus
        candidate.status.trim().toIntOrNull() != null -> candidate.status.trim().toInt()
        candidate.status.equals("AVAILABLE", ignoreCase = true) -> 0
        else -> 1
    }

    private data class IssuedCandidate(val account: String, val candidate: BusinessTenantCandidate)
    private data class AuthenticationAttempt(val ordinal: Long, val generation: Long)
    private data class PreparedStartupSession(
        val connectionId: String,
        val expectedSnapshot: BusinessIdentityRegistrySnapshot,
        val session: BusinessSessionView,
    )
    private data class StartupRestoreAttempt(
        val attempt: AuthenticationAttempt,
        val session: BusinessSessionView?,
    )
}

interface BusinessAuthenticationLifecycleOperations {
    suspend fun restore()
    suspend fun attachAfterReconnect()
    suspend fun onConnectionUnavailable()
    suspend fun close()
}
