package com.wzx.huitai.desktop.auth

import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.desktop.security.BusinessAuthSessionMetadata
import com.wzx.huitai.desktop.security.BusinessAuthSessionMetadataStore
import com.wzx.huitai.desktop.security.BusinessAuthRevocationMarkerPort
import com.wzx.huitai.desktop.security.CredentialPersistenceException
import com.wzx.huitai.desktop.security.LocalCredentialStoreUnavailableException
import com.wzx.huitai.desktop.security.SessionMetadataPersistenceException
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.integration.auth.AuthCredentialPersistencePort
import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthTokenSet
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
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val revocationMarker: BusinessAuthRevocationMarkerPort,
    private val registration: BusinessAgentRegistrationTransactionPort,
    private val identityRegistry: BusinessIdentityRegistry,
    private val actions: IdentityBoundaryActionPort,
    private val desktopInstanceId: String,
    private val desktopSessionId: String,
    private val platformId: Int,
    private val now: () -> Instant = Instant::now,
    private val remoteLogoutTimeoutMillis: Long = 2_000,
    private val cleanupStepTimeoutMillis: Long = 2_000,
    private val operationSettleTimeoutMillis: Long = 15_000,
) : BusinessAuthenticationOperations {
    init {
        require(remoteLogoutTimeoutMillis > 0) { "remoteLogoutTimeoutMillis must be positive" }
        require(cleanupStepTimeoutMillis > 0) { "cleanupStepTimeoutMillis must be positive" }
        require(operationSettleTimeoutMillis > 0) { "operationSettleTimeoutMillis must be positive" }
    }

    constructor(
        preAuthentication: OaPreAuthenticationGateway,
        candidateAuthentication: OaCandidateAuthenticationGateway,
        credentialPersistence: AuthCredentialPersistencePort,
        authSessionManager: AuthSessionManager,
        metadataStore: BusinessAuthSessionMetadataStore,
        revocationMarker: BusinessAuthRevocationMarkerPort,
        registration: BusinessAgentRegistrationTransactionPort,
        identityRegistry: BusinessIdentityRegistry,
        actions: IdentityBoundaryActionPort,
        desktopInstanceId: String,
        desktopSessionId: String,
        platformId: Int,
        now: () -> Instant = Instant::now,
        remoteLogoutTimeoutMillis: Long = 2_000,
        cleanupStepTimeoutMillis: Long = 2_000,
        operationSettleTimeoutMillis: Long = 15_000,
    ) : this(
        preAuthentication,
        candidateAuthentication,
        credentialPersistence,
        authSessionManager,
        StoredBusinessAuthSessionMetadataPort(metadataStore),
        revocationMarker,
        registration,
        identityRegistry,
        actions,
        desktopInstanceId,
        desktopSessionId,
        platformId,
        now,
        remoteLogoutTimeoutMillis,
        cleanupStepTimeoutMillis,
        operationSettleTimeoutMillis,
    )

    private val operationLock = Any()
    private val authorityMutationMutex = Mutex()
    private val mutableLastError = MutableStateFlow<BusinessLoginMessage?>(null)
    private var operationEpoch = 0L
    private var activeOperation: Operation? = null
    private var verifiedSelection: VerifiedTenantSelection? = null
    private var activeCandidate: OaCandidateAccess? = null
    private var activeRevocation: RevocationFlight? = null
    private var closed = false

    override val gate: StateFlow<BusinessAccessGateState> = identityRegistry.gate
    val lastError: StateFlow<BusinessLoginMessage?> = mutableLastError.asStateFlow()

    override suspend fun findTenantCandidates(account: String): List<OaTenantCandidate> {
        val operation = beginOperation(
            BusinessAccessGateState.VERIFYING,
            setOf(BusinessAccessGateState.STARTING, BusinessAccessGateState.SIGNED_OUT),
        )
        return try {
            val candidates = immutableCandidates(preAuthentication.findTenantCandidates(account))
            synchronized(operationLock) {
                checkCurrentLocked(operation)
                verifiedSelection = VerifiedTenantSelection(account, candidates, operation.epoch)
            }
            candidates
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { compensate(operation, OperationResources(), null) }
            throw cancelled
        } catch (failure: Throwable) {
            val code = mapRemoteFailure(failure)
            withContext(NonCancellable) { compensate(operation, OperationResources(), code) }
            throw BusinessAuthenticationException(code)
        } finally {
            endOperation(operation)
        }
    }

    override fun enterTenantSelection() = synchronized(operationLock) {
        ensureOpenLocked()
        check(gate.value == BusinessAccessGateState.VERIFYING && verifiedSelection != null) {
            "Tenant selection requires a verified candidate set"
        }
        identityRegistry.transitionTo(BusinessAccessGateState.SELECTING_TENANT)
    }

    override fun cancelTenantSelection() = synchronized(operationLock) {
        if (closed) return@synchronized
        if (gate.value in setOf(BusinessAccessGateState.VERIFYING, BusinessAccessGateState.SELECTING_TENANT)) {
            operationEpoch += 1
            verifiedSelection = null
            identityRegistry.transitionTo(BusinessAccessGateState.SIGNED_OUT)
        }
    }

    override suspend fun authenticate(
        account: String,
        password: CharArray,
        candidate: OaTenantCandidate,
    ) {
        val operation = try {
            beginAuthenticationOperation(account, candidate)
        } catch (failure: Throwable) {
            Arrays.fill(password, '\u0000')
            throw failure
        }
        val resources = OperationResources()
        try {
            val tokens = try {
                preAuthentication.login(account, password, candidate.tenantId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                throw BusinessAuthenticationException(mapRemoteFailure(failure))
            }
            checkCurrent(operation)
            resources.candidateAccess = candidateAccess(candidate, tokens)
            val permission = loadAndValidatePermission(candidate, tokens, resources.candidateAccess!!)
            commitLocalAuthentication(candidate, tokens, permission, operation, resources)
            finishRegistration(operation, resources)
            commitReady(operation, resources, authorizeExplicitLogin = true)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { compensate(operation, resources, null) }
            throw cancelled
        } catch (failure: BusinessAuthenticationException) {
            withContext(NonCancellable) { compensate(operation, resources, failure.code) }
            throw failure
        } catch (_: Throwable) {
            val code = BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED
            withContext(NonCancellable) { compensate(operation, resources, code) }
            throw BusinessAuthenticationException(code)
        } finally {
            Arrays.fill(password, '\u0000')
            endOperation(operation)
        }
    }

    /** Remembered credentials are intentionally ignored; only the token/metadata pair is evidence. */
    suspend fun restore() {
        val operation = beginOperation(
            BusinessAccessGateState.RESTORING,
            setOf(BusinessAccessGateState.STARTING, BusinessAccessGateState.SIGNED_OUT),
        )
        val resources = OperationResources()
        try {
            if (isDurablyRevoked()) {
                authSessionManager.blockRequestAuthorityImmediately()
                val clearFailed = clearCurrentLocalPair(operation)
                val code = if (clearFailed) BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE else null
                commitSignedOut(operation, code)
                return
            }
            when (val loaded = loadRestorePair()) {
                RestoreLoad.Empty -> {
                    commitSignedOut(operation, null)
                    return
                }
                is RestoreLoad.Invalid -> {
                    val clearFailed = if (loaded.safeToClear) clearCurrentLocalPair(operation) else false
                    val code = if (clearFailed) BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE else loaded.code
                    commitSignedOut(operation, code)
                    return
                }
                is RestoreLoad.Ready -> {
                    checkCurrent(operation)
                    resources.preexistingLocalPair = true
                    val tokens = try {
                        preAuthentication.refresh(loaded.metadata.tenantId, loaded.tokens.refreshToken)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        throw BusinessAuthenticationException(mapRemoteFailure(failure))
                    }
                    checkCurrent(operation)
                    val candidate = restoredCandidate(loaded.metadata)
                    resources.candidateAccess = candidateAccess(candidate, tokens)
                    val permission = loadAndValidatePermission(candidate, tokens, resources.candidateAccess!!)
                    commitLocalAuthentication(candidate, tokens, permission, operation, resources)
                    finishRegistration(operation, resources)
                    commitReady(operation, resources, authorizeExplicitLogin = false)
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { compensate(operation, resources, null) }
            throw cancelled
        } catch (failure: BusinessAuthenticationException) {
            withContext(NonCancellable) { compensate(operation, resources, failure.code) }
        } catch (_: Throwable) {
            withContext(NonCancellable) {
                compensate(operation, resources, BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED)
            }
        } finally {
            endOperation(operation)
        }
    }

    suspend fun logout() = revoke(RevocationReason.LOGOUT, remoteLogout = true)
    suspend fun onAuthenticationExpired() = revoke(RevocationReason.AUTH_EXPIRED, remoteLogout = true)
    suspend fun onMembershipExpired() = revoke(RevocationReason.MEMBERSHIP_EXPIRED, remoteLogout = true)

    override suspend fun onLocalCredentialStoreFailure(code: BusinessLoginErrorCode) =
        revoke(
            reason = if (code == BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE) {
                RevocationReason.LOCAL_KEYSTORE_UNAVAILABLE
            } else {
                RevocationReason.LOCAL_CREDENTIAL_STORE_FAILED
            },
            remoteLogout = true,
        )

    suspend fun close() = revoke(RevocationReason.CLOSE, remoteLogout = false, allowClosed = true)

    override fun toString(): String =
        "BusinessAuthenticationOrchestrator(gate=${gate.value}, identity=[REDACTED], credentials=[REDACTED])"

    private suspend fun commitLocalAuthentication(
        candidate: OaTenantCandidate,
        tokens: OaTokenBundle,
        permission: OaPermissionInfo,
        operation: Operation,
        resources: OperationResources,
    ) {
        authorityMutationMutex.withLock {
            checkCurrent(operation)
            try {
                resources.metadataWritten = true
                metadataPersistence.saveOrReplace(
                    BusinessAuthSessionMetadata(candidate.userId, candidate.tenantId, candidate.platformId.toString()),
                )
                checkCurrent(operation)
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
            val snapshot = authSessionManager.identity.value
                ?: throw BusinessAuthenticationException(BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED)
            resources.authOwner = AuthOwner(snapshot.authSessionId, snapshot.identityEpoch)
            resources.identity = BusinessIdentity(
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
            synchronized(operationLock) {
                checkCurrentLocked(operation)
                identityRegistry.transitionTo(BusinessAccessGateState.REGISTERING_AGENT)
            }
        }
        resources.transaction = try {
            registration.prepare(resources.identity!!)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw BusinessAuthenticationException(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED)
        }
    }

    private suspend fun finishRegistration(operation: Operation, resources: OperationResources) {
        val transaction = resources.transaction
            ?: throw BusinessAuthenticationException(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED)
        try {
            transaction.registerIdentity()
            checkCurrent(operation)
            transaction.registerCapabilityCatalog()
            checkCurrent(operation)
            transaction.registerInitialContext()
            checkCurrent(operation)
            transaction.commit()
            checkCurrent(operation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw BusinessAuthenticationException(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED)
        }
    }

    /** Explicit login authorizes durable records before the single READY+identity publication. */
    private suspend fun commitReady(
        operation: Operation,
        resources: OperationResources,
        authorizeExplicitLogin: Boolean,
    ) = authorityMutationMutex.withLock {
        checkCurrent(operation)
        if (authorizeExplicitLogin) {
            try {
                revocationMarker.clearAfterExplicitLogin()
            } catch (_: Throwable) {
                throw BusinessAuthenticationException(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE)
            }
        }
        checkCurrent(operation)
        synchronized(operationLock) {
            checkCurrentLocked(operation)
            val identity = resources.identity
                ?: throw BusinessAuthenticationException(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED)
            val candidate = resources.candidateAccess
                ?: throw BusinessAuthenticationException(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED)
            activeCandidate = candidate
            if (!identityRegistry.publishReady(identity, operation.registryGeneration)) {
                activeCandidate = null
                throw CancellationException("Authentication registry generation changed")
            }
            mutableLastError.value = null
        }
    }

    private suspend fun compensate(
        operation: Operation,
        resources: OperationResources,
        errorCode: BusinessLoginErrorCode?,
    ) {
        val hasLocalEffects = resources.preexistingLocalPair ||
            resources.metadataWritten ||
            resources.authOwner != null ||
            resources.transaction != null ||
            resources.identity != null
        var ownsAuthority = false
        var durableFailure = false
        authorityMutationMutex.withLock {
            if (!isOperationAuthorityCurrent(operation)) {
                resources.authOwner?.let { owner ->
                    authSessionManager.blockRequestAuthorityIfCurrent(owner.authSessionId, owner.identityEpoch)
                }
                return@withLock
            }
            ownsAuthority = true
            if (!hasLocalEffects) return@withLock

            identityRegistry.invalidate(BusinessAccessGateState.SIGNING_OUT)
            val owner = resources.authOwner
            if (owner == null) {
                authSessionManager.blockRequestAuthorityImmediately()
            } else {
                authSessionManager.blockRequestAuthorityIfCurrent(owner.authSessionId, owner.identityEpoch)
            }
            synchronized(operationLock) {
                if (isOperationAuthorityCurrentLocked(operation)) {
                    activeCandidate = null
                }
            }

            try {
                revocationMarker.markRevoked()
            } catch (_: Throwable) {
                durableFailure = true
            }
            try {
                authSessionManager.failClosedRevoke()
            } catch (_: Throwable) {
                durableFailure = true
            }
            try {
                metadataPersistence.clear()
            } catch (_: Throwable) {
                durableFailure = true
            }
        }

        boundedCleanup { resources.transaction?.rollback() }
        boundedCandidateLogout(resources.candidateAccess)
        if (!ownsAuthority) return
        if (hasLocalEffects) {
            resources.identity?.let { revokeActionsBestEffort(it) }
            boundedCleanup { registration.publishSignedOut() }
            boundedCleanup { registration.clearWorkspace() }
        }
        synchronized(operationLock) {
            if (isOperationAuthorityCurrentLocked(operation)) {
                verifiedSelection = null
                activeCandidate = null
                mutableLastError.value = when {
                    durableFailure -> BusinessLoginMessage(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE)
                    errorCode != null -> BusinessLoginMessage(errorCode)
                    else -> null
                }
                identityRegistry.transitionTo(BusinessAccessGateState.SIGNED_OUT)
            }
        }
    }

    private suspend fun revoke(
        reason: RevocationReason,
        remoteLogout: Boolean,
        allowClosed: Boolean = false,
    ) {
        val callerJob = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            val claim = synchronized(operationLock) {
                val wasClosed = closed
                if (reason == RevocationReason.CLOSE) closed = true
                activeRevocation?.let { return@synchronized RevocationClaim(it, null) }
                if (reason == RevocationReason.CLOSE && wasClosed) {
                    val completed = RevocationOutcome(
                        visibleError = null,
                        terminalFailure = null,
                        safeToRelease = true,
                    )
                    return@synchronized RevocationClaim(
                        RevocationFlight(CompletableDeferred(completed)),
                        null,
                    )
                }
                if (!allowClosed) ensureOpenLocked()
                val flight = RevocationFlight(CompletableDeferred())
                activeRevocation = flight
                operationEpoch += 1
                val operation = activeOperation
                activeOperation = null
                verifiedSelection = null
                val identity = identityRegistry.invalidate(BusinessAccessGateState.SIGNING_OUT)
                val candidate = activeCandidate
                activeCandidate = null
                RevocationClaim(flight, RevokedAuthority(operation?.job, identity, candidate))
            }
            val revoked = claim.revokedAuthority
            if (revoked == null) {
                claim.flight.completion.await().throwIfFailed()
                return@withContext
            }

            val outcome = try {
                performRevocation(revoked, reason, remoteLogout, callerJob)
            } catch (_: Throwable) {
                RevocationOutcome(
                    visibleError = BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE,
                    terminalFailure = BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE,
                    safeToRelease = false,
                )
            }
            synchronized(operationLock) {
                check(activeRevocation === claim.flight) { "revocation ownership changed" }
                mutableLastError.value = outcome.visibleError?.let(::BusinessLoginMessage)
                if (outcome.safeToRelease) {
                    identityRegistry.transitionTo(BusinessAccessGateState.SIGNED_OUT)
                }
                claim.flight.completion.complete(outcome)
                if (outcome.safeToRelease) activeRevocation = null
            }
            outcome.throwIfFailed()
        }
    }

    private suspend fun performRevocation(
        revoked: RevokedAuthority,
        reason: RevocationReason,
        remoteLogout: Boolean,
        callerJob: Job?,
    ): RevocationOutcome {
        authSessionManager.blockRequestAuthorityImmediately()
        revoked.operationJob?.cancel(CancellationException("Authentication operation revoked"))

        var markerRevoked = false
        var tokenRecordCleared = false
        var metadataRecordCleared = false
        var persistenceFailure = false
        authorityMutationMutex.withLock {
            try {
                revocationMarker.markRevoked()
                markerRevoked = true
            } catch (_: Throwable) {
                persistenceFailure = true
            }
            try {
                authSessionManager.failClosedRevoke()
                tokenRecordCleared = true
            } catch (_: Throwable) {
                persistenceFailure = true
            }
            try {
                metadataPersistence.clear()
                metadataRecordCleared = true
            } catch (_: Throwable) {
                persistenceFailure = true
            }
        }
        if (!awaitRevokedOperationSettlement(revoked.operationJob, callerJob)) {
            return RevocationOutcome(
                visibleError = BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE,
                terminalFailure = BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE,
                safeToRelease = false,
            )
        }
        revoked.identity?.let { revokeActionsBestEffort(it) }
        boundedCleanup { registration.publishSignedOut() }
        boundedCleanup { registration.clearWorkspace() }
        if (remoteLogout) boundedCandidateLogout(revoked.candidate)

        val durableFailure = !markerRevoked && !(tokenRecordCleared && metadataRecordCleared)
        val visibleError = when {
            persistenceFailure -> BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE
            reason == RevocationReason.AUTH_EXPIRED -> BusinessLoginErrorCode.AUTH_EXPIRED
            reason == RevocationReason.MEMBERSHIP_EXPIRED -> BusinessLoginErrorCode.MEMBERSHIP_EXPIRED
            reason == RevocationReason.LOCAL_KEYSTORE_UNAVAILABLE -> BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE
            reason == RevocationReason.LOCAL_CREDENTIAL_STORE_FAILED ->
                BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED
            else -> null
        }
        return RevocationOutcome(
            visibleError = visibleError,
            terminalFailure = BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE.takeIf { durableFailure },
            safeToRelease = true,
        )
    }

    private suspend fun awaitRevokedOperationSettlement(operationJob: Job?, callerJob: Job?): Boolean {
        if (operationJob == null) return true
        if (operationJob === callerJob) return false
        return withTimeoutOrNull(operationSettleTimeoutMillis) {
            operationJob.join()
            true
        } ?: false
    }

    private suspend fun clearCurrentLocalPair(operation: Operation): Boolean = authorityMutationMutex.withLock {
        checkCurrent(operation)
        var durableFailure = false
        try {
            revocationMarker.markRevoked()
        } catch (_: Throwable) {
            durableFailure = true
        }
        try {
            authSessionManager.failClosedRevoke()
        } catch (_: Throwable) {
            durableFailure = true
        }
        try {
            metadataPersistence.clear()
        } catch (_: Throwable) {
            durableFailure = true
        }
        durableFailure
    }

    private fun commitSignedOut(operation: Operation, code: BusinessLoginErrorCode?) = synchronized(operationLock) {
        checkCurrentLocked(operation)
        verifiedSelection = null
        activeCandidate = null
        mutableLastError.value = code?.let(::BusinessLoginMessage)
        identityRegistry.transitionTo(BusinessAccessGateState.SIGNED_OUT)
    }

    private fun isDurablyRevoked(): Boolean = try {
        revocationMarker.isRevoked()
    } catch (_: Throwable) {
        throw BusinessAuthenticationException(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE)
    }

    private suspend fun loadRestorePair(): RestoreLoad {
        var tokens: AuthTokenSet? = null
        var metadata: BusinessAuthSessionMetadata? = null
        var invalidCode: BusinessLoginErrorCode? = null
        var safeToClear = true
        try {
            tokens = credentialPersistence.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LocalCredentialStoreUnavailableException) {
            invalidCode = BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE
            safeToClear = false
        } catch (_: CredentialPersistenceException) {
            invalidCode = BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED
        } catch (_: Throwable) {
            invalidCode = BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED
        }
        try {
            metadata = metadataPersistence.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LocalCredentialStoreUnavailableException) {
            invalidCode = BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE
            safeToClear = false
        } catch (_: SessionMetadataPersistenceException) {
            if (invalidCode == null) invalidCode = BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED
        } catch (_: Throwable) {
            if (invalidCode == null) invalidCode = BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED
        }
        if (invalidCode != null) return RestoreLoad.Invalid(invalidCode, safeToClear)
        if (tokens == null && metadata == null) return RestoreLoad.Empty
        if (tokens == null || metadata == null) {
            return RestoreLoad.Invalid(BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED, safeToClear = true)
        }
        return RestoreLoad.Ready(tokens, metadata)
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

    private suspend fun revokeActionsBestEffort(identity: BusinessIdentity) {
        boundedCleanup { actions.cancelPreExecution(identity.actionScope(), PRE_EXECUTION_STATES) }
        boundedCleanup { actions.detachExecutingForReconciliation(identity.actionScope()) }
    }

    private suspend fun boundedCandidateLogout(candidate: OaCandidateAccess?) {
        if (candidate == null) return
        boundedCleanup(remoteLogoutTimeoutMillis) { candidateAuthentication.logout(candidate) }
    }

    private suspend fun boundedCleanup(
        timeoutMillis: Long = cleanupStepTimeoutMillis,
        block: suspend () -> Unit,
    ) {
        withTimeoutOrNull(timeoutMillis) {
            supervisorScope {
                val adapter = async { block() }
                try {
                    adapter.await()
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    // Adapter cancellation is confined to its supervisor child.
                } catch (_: Throwable) {
                    // Stable primary error and fail-closed local authority remain authoritative.
                }
            }
        }
    }

    private suspend fun beginOperation(
        target: BusinessAccessGateState,
        allowedSources: Set<BusinessAccessGateState>,
    ): Operation {
        val context = currentCoroutineContext()
        context.ensureActive()
        val job = context[Job] ?: error("Authentication operation requires a coroutine Job")
        return synchronized(operationLock) {
            ensureOpenLocked()
            if (activeRevocation != null || activeOperation?.job?.isActive == true || gate.value !in allowedSources) {
                throw BusinessAuthenticationException(BusinessLoginErrorCode.AUTHENTICATION_IN_PROGRESS)
            }
            operationEpoch += 1
            verifiedSelection = null
            Operation(operationEpoch, job, identityRegistry.currentGeneration()).also {
                activeOperation = it
                identityRegistry.transitionTo(target)
                mutableLastError.value = null
            }
        }
    }

    private suspend fun beginAuthenticationOperation(
        account: String,
        candidate: OaTenantCandidate,
    ): Operation {
        val context = currentCoroutineContext()
        context.ensureActive()
        val job = context[Job] ?: error("Authentication operation requires a coroutine Job")
        return synchronized(operationLock) {
            ensureOpenLocked()
            val verified = verifiedSelection
            if (
                activeOperation?.job?.isActive == true ||
                activeRevocation != null ||
                gate.value !in setOf(BusinessAccessGateState.VERIFYING, BusinessAccessGateState.SELECTING_TENANT) ||
                verified == null ||
                verified.account != account ||
                candidate !in verified.candidates
            ) {
                throw BusinessAuthenticationException(BusinessLoginErrorCode.AUTHENTICATION_IN_PROGRESS)
            }
            operationEpoch += 1
            verifiedSelection = null
            Operation(operationEpoch, job, identityRegistry.currentGeneration()).also {
                activeOperation = it
                identityRegistry.transitionTo(BusinessAccessGateState.AUTHENTICATING)
                mutableLastError.value = null
            }
        }
    }

    private fun checkCurrent(operation: Operation) = synchronized(operationLock) {
        checkCurrentLocked(operation)
    }

    private fun checkCurrentLocked(operation: Operation) {
        if (!isCurrentLocked(operation)) throw CancellationException("Authentication operation superseded")
    }

    private fun isCurrentLocked(operation: Operation): Boolean =
        !closed && operation.job.isActive && operation.epoch == operationEpoch && activeOperation === operation

    private fun isOperationAuthorityCurrent(operation: Operation): Boolean = synchronized(operationLock) {
        isOperationAuthorityCurrentLocked(operation)
    }

    private fun isOperationAuthorityCurrentLocked(operation: Operation): Boolean =
        !closed && operation.epoch == operationEpoch && activeOperation === operation

    private fun endOperation(operation: Operation) = synchronized(operationLock) {
        if (activeOperation === operation) activeOperation = null
    }

    private fun ensureOpenLocked() {
        check(!closed) { "Authentication orchestrator is closed" }
    }

    private fun immutableCandidates(values: List<OaTenantCandidate>): List<OaTenantCandidate> =
        Collections.unmodifiableList(ArrayList(values))

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

    private data class Operation(val epoch: Long, val job: Job, val registryGeneration: Long)
    private data class VerifiedTenantSelection(
        val account: String,
        val candidates: List<OaTenantCandidate>,
        val verificationEpoch: Long,
    )
    private data class AuthOwner(val authSessionId: String, val identityEpoch: Long)
    private data class OperationResources(
        var candidateAccess: OaCandidateAccess? = null,
        var transaction: BusinessAgentRegistrationTransaction? = null,
        var identity: BusinessIdentity? = null,
        var authOwner: AuthOwner? = null,
        var metadataWritten: Boolean = false,
        var preexistingLocalPair: Boolean = false,
    )
    private data class RevokedAuthority(
        val operationJob: Job?,
        val identity: BusinessIdentity?,
        val candidate: OaCandidateAccess?,
    )
    private data class RevocationFlight(val completion: CompletableDeferred<RevocationOutcome>)
    private data class RevocationClaim(
        val flight: RevocationFlight,
        val revokedAuthority: RevokedAuthority?,
    )
    private data class RevocationOutcome(
        val visibleError: BusinessLoginErrorCode?,
        val terminalFailure: BusinessLoginErrorCode?,
        val safeToRelease: Boolean,
    ) {
        fun throwIfFailed() {
            terminalFailure?.let { throw BusinessAuthenticationException(it) }
        }
    }

    private sealed interface RestoreLoad {
        data object Empty : RestoreLoad
        data class Ready(val tokens: AuthTokenSet, val metadata: BusinessAuthSessionMetadata) : RestoreLoad
        data class Invalid(val code: BusinessLoginErrorCode, val safeToClear: Boolean) : RestoreLoad
    }

    private enum class RevocationReason {
        LOGOUT,
        AUTH_EXPIRED,
        MEMBERSHIP_EXPIRED,
        LOCAL_KEYSTORE_UNAVAILABLE,
        LOCAL_CREDENTIAL_STORE_FAILED,
        CLOSE,
    }

    private companion object {
        val PRE_EXECUTION_STATES = setOf(
            ActionExecutionState.RECEIVED,
            ActionExecutionState.VALIDATING,
            ActionExecutionState.PREVIEWED,
            ActionExecutionState.WAITING_APPROVAL,
        )
    }
}
