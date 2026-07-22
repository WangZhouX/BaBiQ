package com.wzx.huitai.desktop.auth

import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.desktop.security.BusinessAuthSessionMetadata
import com.wzx.huitai.desktop.security.BusinessAuthSessionMetadataStore
import com.wzx.huitai.desktop.security.LocalCredentialStoreUnavailableException
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.integration.auth.AuthCredentialPersistencePort
import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.integration.auth.AuthenticationState
import com.wzx.huitai.integration.identity.IdentityBoundaryActionPort
import com.wzx.huitai.integration.oa.auth.OaAuthenticationError
import com.wzx.huitai.integration.oa.auth.OaAuthenticationException
import com.wzx.huitai.integration.oa.auth.OaCandidateAccess
import com.wzx.huitai.integration.oa.auth.OaCandidateAuthenticationGateway
import com.wzx.huitai.integration.oa.auth.OaPermissionInfo
import com.wzx.huitai.integration.oa.auth.OaPreAuthenticationGateway
import com.wzx.huitai.integration.oa.auth.OaTenantCandidate
import com.wzx.huitai.integration.oa.auth.OaTokenBundle
import java.time.Instant
import java.util.Arrays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface BusinessAuthSessionMetadataPersistencePort {
    fun load(): BusinessAuthSessionMetadata?
    fun saveOrReplace(metadata: BusinessAuthSessionMetadata)
    fun clear()
}

class StoredBusinessAuthSessionMetadataPort(
    private val store: BusinessAuthSessionMetadataStore,
) : BusinessAuthSessionMetadataPersistencePort {
    override fun load(): BusinessAuthSessionMetadata? = store.load()
    override fun saveOrReplace(metadata: BusinessAuthSessionMetadata) = store.saveOrReplace(metadata)
    override fun clear() = store.clear()
}

/** Sole authority for local auth commit, Agent registration, reconnect identity and revocation. */
class BusinessAuthenticationOrchestrator(
    private val preAuthentication: OaPreAuthenticationGateway,
    private val candidateAuthentication: OaCandidateAuthenticationGateway,
    private val credentialPersistence: AuthCredentialPersistencePort,
    private val authSessionManager: AuthSessionManager,
    private val metadataPersistence: BusinessAuthSessionMetadataPersistencePort,
    private val registration: BusinessAgentRegistrationTransactionPort,
    private val identityRegistry: BusinessIdentityRegistry,
    private val actions: IdentityBoundaryActionPort,
    private val desktopInstanceId: String,
    private val desktopSessionId: String,
    private val platformId: Int,
    private val now: () -> Instant = Instant::now,
) : BusinessAuthenticationOperations {
    constructor(
        preAuthentication: OaPreAuthenticationGateway,
        candidateAuthentication: OaCandidateAuthenticationGateway,
        credentialPersistence: AuthCredentialPersistencePort,
        authSessionManager: AuthSessionManager,
        metadataStore: BusinessAuthSessionMetadataStore,
        registration: BusinessAgentRegistrationTransactionPort,
        identityRegistry: BusinessIdentityRegistry,
        actions: IdentityBoundaryActionPort,
        desktopInstanceId: String,
        desktopSessionId: String,
        platformId: Int,
        now: () -> Instant = Instant::now,
    ) : this(
        preAuthentication,
        candidateAuthentication,
        credentialPersistence,
        authSessionManager,
        StoredBusinessAuthSessionMetadataPort(metadataStore),
        registration,
        identityRegistry,
        actions,
        desktopInstanceId,
        desktopSessionId,
        platformId,
        now,
    )

    private val operationLock = Any()
    private val mutableGate = MutableStateFlow(BusinessAccessGateState.STARTING)
    private val mutableLastError = MutableStateFlow<BusinessLoginMessage?>(null)
    private var operationEpoch = 0L
    private var activeOperation: Job? = null
    private var closed = false
    private var activeCandidate: OaCandidateAccess? = null

    override val gate: StateFlow<BusinessAccessGateState> = mutableGate.asStateFlow()
    val lastError: StateFlow<BusinessLoginMessage?> = mutableLastError.asStateFlow()

    override suspend fun findTenantCandidates(account: String): List<OaTenantCandidate> {
        val operation = beginOperation(BusinessAccessGateState.VERIFYING)
        return try {
            preAuthentication.findTenantCandidates(account)
        } catch (cancelled: CancellationException) {
            if (isOperationGenerationCurrent(operation)) moveGate(BusinessAccessGateState.SIGNED_OUT)
            throw cancelled
        } catch (failure: Throwable) {
            val mapped = mapRemoteFailure(failure)
            failToSignedOut(mapped)
            throw BusinessAuthenticationException(mapped)
        } finally {
            endOperation(operation)
        }
    }

    override fun enterTenantSelection() {
        ensureOpen()
        check(mutableGate.value == BusinessAccessGateState.VERIFYING) {
            "Tenant selection requires VERIFYING gate"
        }
        moveGate(BusinessAccessGateState.SELECTING_TENANT)
    }

    override fun cancelTenantSelection() {
        if (closed) return
        if (mutableGate.value in setOf(BusinessAccessGateState.VERIFYING, BusinessAccessGateState.SELECTING_TENANT)) {
            moveGate(BusinessAccessGateState.SIGNED_OUT)
        }
    }

    override suspend fun authenticate(
        account: String,
        password: CharArray,
        candidate: OaTenantCandidate,
    ) {
        val operation = try {
            beginOperation(BusinessAccessGateState.AUTHENTICATING)
        } catch (failure: Throwable) {
            Arrays.fill(password, '\u0000')
            throw failure
        }
        var access: OaCandidateAccess? = null
        var transaction: BusinessAgentRegistrationTransaction? = null
        var localIdentity: BusinessIdentity? = null
        try {
            val tokens = try {
                preAuthentication.login(account, password, candidate.tenantId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                throw BusinessAuthenticationException(mapRemoteFailure(failure))
            }
            access = candidateAccess(candidate, tokens)
            val permission = loadAndValidatePermission(candidate, tokens, access)
            val commit = commitLocalAuthentication(candidate, tokens, permission, operation)
            transaction = commit.transaction
            localIdentity = commit.identity
            finishRegistration(commit, operation)
            activeCandidate = access
        } catch (cancelled: CancellationException) {
            compensate(access, transaction, localIdentity, operation, null)
            throw cancelled
        } catch (failure: BusinessAuthenticationException) {
            compensate(access, transaction, localIdentity, operation, failure.code)
            throw failure
        } catch (_: Throwable) {
            compensate(
                access,
                transaction,
                localIdentity,
                operation,
                BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED,
            )
            throw BusinessAuthenticationException(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED)
        } finally {
            Arrays.fill(password, '\u0000')
            endOperation(operation)
        }
    }

    /** Remembered credentials are intentionally ignored; only the token/metadata pair is evidence. */
    suspend fun restore() {
        ensureOpen()
        val pair = loadRestorePair() ?: return
        val operation = beginOperation(BusinessAccessGateState.RESTORING)
        var access: OaCandidateAccess? = null
        var transaction: BusinessAgentRegistrationTransaction? = null
        var localIdentity: BusinessIdentity? = null
        try {
            val tokens = try {
                preAuthentication.refresh(pair.metadata.tenantId, pair.tokens.refreshToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                throw BusinessAuthenticationException(mapRemoteFailure(failure))
            }
            val candidate = restoredCandidate(pair.metadata)
            access = candidateAccess(candidate, tokens)
            val permission = loadAndValidatePermission(candidate, tokens, access)
            val commit = commitLocalAuthentication(candidate, tokens, permission, operation)
            transaction = commit.transaction
            localIdentity = commit.identity
            finishRegistration(commit, operation)
            activeCandidate = access
        } catch (cancelled: CancellationException) {
            compensate(access, transaction, localIdentity, operation, null)
            throw cancelled
        } catch (failure: BusinessAuthenticationException) {
            compensate(access, transaction, localIdentity, operation, failure.code)
        } catch (_: Throwable) {
            compensate(
                access,
                transaction,
                localIdentity,
                operation,
                BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED,
            )
        } finally {
            endOperation(operation)
        }
    }

    suspend fun logout() = revoke(RevocationReason.LOGOUT, remoteLogout = true)

    suspend fun onAuthenticationExpired() = revoke(RevocationReason.AUTH_EXPIRED, remoteLogout = true)

    suspend fun onMembershipExpired() = revoke(RevocationReason.MEMBERSHIP_EXPIRED, remoteLogout = true)

    suspend fun close() {
        val shouldClose = synchronized(operationLock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!shouldClose) return
        revoke(RevocationReason.CLOSE, remoteLogout = false, allowClosed = true)
    }

    override fun toString(): String =
        "BusinessAuthenticationOrchestrator(gate=${mutableGate.value}, identity=[REDACTED], credentials=[REDACTED])"

    private suspend fun commitLocalAuthentication(
        candidate: OaTenantCandidate,
        tokens: OaTokenBundle,
        permission: OaPermissionInfo,
        operation: Operation,
    ): LocalCommit {
        checkCurrent(operation)
        val metadata = BusinessAuthSessionMetadata(candidate.userId, candidate.tenantId, candidate.platformId.toString())
        try {
            metadataPersistence.saveOrReplace(metadata)
            authSessionManager.login(
                userId = candidate.userId,
                tenantId = candidate.tenantId,
                platformId = candidate.platformId.toString(),
                roles = permission.roles,
                permissions = permission.permissions,
                authenticatedAt = now(),
                tokens = AuthTokenSet(tokens.accessToken, tokens.refreshToken),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LocalCredentialStoreUnavailableException) {
            throw BusinessAuthenticationException(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE)
        } catch (_: Throwable) {
            throw BusinessAuthenticationException(BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED)
        }
        checkCurrent(operation)
        val snapshot = authSessionManager.identity.value
            ?: throw BusinessAuthenticationException(BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED)
        val identity = BusinessIdentity(
            desktopInstanceId = desktopInstanceId,
            desktopSessionId = desktopSessionId,
            authSessionId = snapshot.authSessionId,
            identityEpoch = snapshot.identityEpoch,
            userId = snapshot.userId,
            tenantId = snapshot.tenantId,
            platformId = snapshot.platformId,
            roles = snapshot.roles,
            permissions = snapshot.permissions,
        )
        moveGateIfCurrent(operation, BusinessAccessGateState.REGISTERING_AGENT)
        val transaction = try {
            registration.prepare(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw BusinessAuthenticationException(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED)
        }
        return LocalCommit(identity, transaction)
    }

    private suspend fun finishRegistration(commit: LocalCommit, operation: Operation) {
        try {
            commit.transaction.registerIdentity()
            checkCurrent(operation)
            commit.transaction.registerCapabilityCatalog()
            checkCurrent(operation)
            commit.transaction.registerInitialContext()
            checkCurrent(operation)
            commit.transaction.commit()
            checkCurrent(operation)
            if (!identityRegistry.install(commit.identity, operation.registryGeneration)) throw CancellationException()
            moveGateIfCurrent(operation, BusinessAccessGateState.READY)
            mutableLastError.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw BusinessAuthenticationException(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED)
        }
    }

    private fun candidateAccess(candidate: OaTenantCandidate, tokens: OaTokenBundle): OaCandidateAccess =
        OaCandidateAccess(tokens.userId, candidate.tenantId, candidate.platformId, tokens.accessToken)

    private suspend fun loadAndValidatePermission(
        candidate: OaTenantCandidate,
        tokens: OaTokenBundle,
        access: OaCandidateAccess,
    ): OaPermissionInfo {
        val permission = try {
            candidateAuthentication.loadPermissionInfo(access)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw BusinessAuthenticationException(BusinessLoginErrorCode.PERMISSION_LOAD_FAILED)
        }
        if (
            candidate.userId != tokens.userId ||
            candidate.userId != permission.user.id ||
            candidate.tenantId != access.tenantId ||
            candidate.platformId != access.platformId ||
            candidate.platformId != platformId
        ) {
            throw BusinessAuthenticationException(BusinessLoginErrorCode.PERMISSION_LOAD_FAILED)
        }
        return permission
    }

    private fun restoredCandidate(metadata: BusinessAuthSessionMetadata): OaTenantCandidate {
        val restoredPlatform = metadata.platformId.toIntOrNull()
        if (restoredPlatform != platformId) {
            throw BusinessAuthenticationException(BusinessLoginErrorCode.PERMISSION_LOAD_FAILED)
        }
        return OaTenantCandidate(metadata.userId, metadata.tenantId, restoredPlatform, tenantEnterStatus = 0)
    }

    private suspend fun loadRestorePair(): RestorePair? {
        var tokens: AuthTokenSet? = null
        var metadata: BusinessAuthSessionMetadata? = null
        var invalid = false
        try {
            tokens = credentialPersistence.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            invalid = true
        }
        try {
            metadata = metadataPersistence.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            invalid = true
        }
        if (!invalid && tokens == null && metadata == null) {
            moveGate(BusinessAccessGateState.SIGNED_OUT)
            mutableLastError.value = null
            return null
        }
        if (invalid || tokens == null || metadata == null) {
            clearLocalPairBestEffort()
            identityRegistry.invalidate()
            moveGate(BusinessAccessGateState.SIGNED_OUT)
            if (invalid) mutableLastError.value = BusinessLoginMessage(BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED)
            return null
        }
        return RestorePair(tokens, metadata)
    }

    private suspend fun compensate(
        candidate: OaCandidateAccess?,
        transaction: BusinessAgentRegistrationTransaction?,
        identity: BusinessIdentity?,
        operation: Operation,
        errorCode: BusinessLoginErrorCode?,
    ) {
        tryBestEffort { transaction?.rollback() }
        identityRegistry.invalidate()
        if (identity != null) revokeActionsBestEffort(identity)
        tryBestEffort { registration.publishSignedOut() }
        tryBestEffort { registration.clearWorkspace() }
        tryBestEffort { candidate?.let { candidateAuthentication.logout(it) } }
        clearLocalAuthenticationBestEffort(RevocationReason.LOGOUT)
        if (isOperationGenerationCurrent(operation)) {
            mutableLastError.value = errorCode?.let(::BusinessLoginMessage)
            moveGate(BusinessAccessGateState.SIGNED_OUT)
        }
    }

    private suspend fun revoke(
        reason: RevocationReason,
        remoteLogout: Boolean,
        allowClosed: Boolean = false,
    ) {
        if (!allowClosed) ensureOpen()
        val operationToCancel: Job?
        synchronized(operationLock) {
            operationEpoch += 1
            operationToCancel = activeOperation
            activeOperation = null
            mutableGate.value = BusinessAccessGateState.SIGNING_OUT
        }
        operationToCancel?.cancel(CancellationException("Authentication operation revoked"))
        val oldIdentity = identityRegistry.invalidate()
        val oldCandidate = activeCandidate
        activeCandidate = null
        if (oldIdentity != null) revokeActionsBestEffort(oldIdentity)
        tryBestEffort { registration.publishSignedOut() }
        tryBestEffort { registration.clearWorkspace() }
        if (remoteLogout) tryBestEffort { oldCandidate?.let { candidateAuthentication.logout(it) } }
        clearLocalAuthenticationBestEffort(reason)
        mutableLastError.value = when (reason) {
            RevocationReason.AUTH_EXPIRED -> BusinessLoginMessage(BusinessLoginErrorCode.AUTH_EXPIRED)
            RevocationReason.MEMBERSHIP_EXPIRED -> BusinessLoginMessage(BusinessLoginErrorCode.MEMBERSHIP_EXPIRED)
            else -> null
        }
        mutableGate.value = BusinessAccessGateState.SIGNED_OUT
    }

    private suspend fun revokeActionsBestEffort(identity: BusinessIdentity) {
        tryBestEffort {
            actions.cancelPreExecution(identity.actionScope(), PRE_EXECUTION_STATES)
        }
        tryBestEffort { actions.detachExecutingForReconciliation(identity.actionScope()) }
    }

    private suspend fun clearLocalAuthenticationBestEffort(reason: RevocationReason) {
        tryBestEffort {
            when (reason) {
                RevocationReason.AUTH_EXPIRED -> if (authSessionManager.state.value == AuthenticationState.AUTHENTICATED) {
                    authSessionManager.expireAuthentication()
                } else clearSessionManagerOrCredentials()
                RevocationReason.MEMBERSHIP_EXPIRED -> if (authSessionManager.state.value == AuthenticationState.AUTHENTICATED) {
                    authSessionManager.expireMembership()
                } else clearSessionManagerOrCredentials()
                else -> clearSessionManagerOrCredentials()
            }
        }
        clearLocalPairBestEffort()
    }

    private suspend fun clearSessionManagerOrCredentials() {
        if (authSessionManager.state.value == AuthenticationState.SIGNED_OUT) {
            credentialPersistence.clear()
        } else {
            authSessionManager.logout()
        }
    }

    private suspend fun clearLocalPairBestEffort() {
        try {
            credentialPersistence.clear()
        } catch (_: Throwable) {
            // Compensation must not replace the primary stable error.
        }
        try {
            metadataPersistence.clear()
        } catch (_: Throwable) {
            // Compensation must not replace the primary stable error.
        }
    }

    private suspend fun beginOperation(target: BusinessAccessGateState): Operation {
        val context = currentCoroutineContext()
        context.ensureActive()
        val job = context[Job]
            ?: error("Authentication operation requires a coroutine Job")
        return synchronized(operationLock) {
            check(!closed) { "Authentication orchestrator is closed" }
            if (activeOperation?.isActive == true) {
                throw BusinessAuthenticationException(BusinessLoginErrorCode.AUTHENTICATION_IN_PROGRESS)
            }
            if (mutableGate.value !in allowedSources(target)) {
                throw BusinessAuthenticationException(BusinessLoginErrorCode.AUTHENTICATION_IN_PROGRESS)
            }
            operationEpoch += 1
            activeOperation = job
            mutableGate.value = target
            mutableLastError.value = null
            Operation(operationEpoch, job, identityRegistry.currentGeneration())
        }
    }

    private fun endOperation(operation: Operation) = synchronized(operationLock) {
        if (activeOperation === operation.job) activeOperation = null
    }

    private fun checkCurrent(operation: Operation) {
        if (!isCurrent(operation)) throw CancellationException("Authentication operation superseded")
    }

    private fun isCurrent(operation: Operation): Boolean = synchronized(operationLock) {
        !closed && operation.job.isActive && operation.epoch == operationEpoch && activeOperation === operation.job
    }

    private fun isOperationGenerationCurrent(operation: Operation): Boolean = synchronized(operationLock) {
        !closed && operation.epoch == operationEpoch && activeOperation === operation.job
    }

    private fun moveGateIfCurrent(operation: Operation, target: BusinessAccessGateState) {
        checkCurrent(operation)
        mutableGate.value = target
    }

    private fun moveGate(target: BusinessAccessGateState) {
        mutableGate.value = target
    }

    private fun failToSignedOut(code: BusinessLoginErrorCode) {
        mutableLastError.value = BusinessLoginMessage(code)
        mutableGate.value = BusinessAccessGateState.SIGNED_OUT
    }

    private fun ensureOpen() {
        check(!closed) { "Authentication orchestrator is closed" }
    }

    private fun allowedSources(target: BusinessAccessGateState): Set<BusinessAccessGateState> = when (target) {
        BusinessAccessGateState.VERIFYING -> setOf(
            BusinessAccessGateState.STARTING,
            BusinessAccessGateState.SIGNED_OUT,
        )
        BusinessAccessGateState.AUTHENTICATING -> setOf(
            BusinessAccessGateState.STARTING,
            BusinessAccessGateState.SIGNED_OUT,
            BusinessAccessGateState.VERIFYING,
            BusinessAccessGateState.SELECTING_TENANT,
        )
        BusinessAccessGateState.RESTORING -> setOf(
            BusinessAccessGateState.STARTING,
            BusinessAccessGateState.SIGNED_OUT,
        )
        else -> emptySet()
    }

    private fun mapRemoteFailure(failure: Throwable): BusinessLoginErrorCode =
        if (failure is OaAuthenticationException) {
            when (failure.error) {
                OaAuthenticationError.INVALID_CREDENTIALS,
                OaAuthenticationError.INVALID_PASSWORD_FORMAT,
                -> BusinessLoginErrorCode.INVALID_CREDENTIALS
                OaAuthenticationError.ACCOUNT_NOT_FOUND -> BusinessLoginErrorCode.ACCOUNT_NOT_FOUND
                OaAuthenticationError.REMOTE_PROTOCOL_ERROR -> BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR
                OaAuthenticationError.REMOTE_UNAVAILABLE -> BusinessLoginErrorCode.REMOTE_UNAVAILABLE
                OaAuthenticationError.REMOTE_TIMEOUT -> BusinessLoginErrorCode.REMOTE_TIMEOUT
            }
        } else {
            BusinessLoginErrorCode.REMOTE_UNAVAILABLE
        }

    private suspend fun tryBestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Local revocation continues and the primary stable error remains authoritative.
        }
    }

    private data class Operation(val epoch: Long, val job: Job, val registryGeneration: Long)
    private data class LocalCommit(
        val identity: BusinessIdentity,
        val transaction: BusinessAgentRegistrationTransaction,
    )
    private data class RestorePair(val tokens: AuthTokenSet, val metadata: BusinessAuthSessionMetadata)
    private enum class RevocationReason { LOGOUT, AUTH_EXPIRED, MEMBERSHIP_EXPIRED, CLOSE }

    private companion object {
        val PRE_EXECUTION_STATES = setOf(
            ActionExecutionState.RECEIVED,
            ActionExecutionState.VALIDATING,
            ActionExecutionState.PREVIEWED,
            ActionExecutionState.WAITING_APPROVAL,
        )

    }
}
