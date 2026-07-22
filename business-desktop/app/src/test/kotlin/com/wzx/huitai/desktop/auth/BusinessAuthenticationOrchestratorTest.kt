package com.wzx.huitai.desktop.auth

import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.desktop.security.BusinessAuthSessionMetadata
import com.wzx.huitai.desktop.security.BusinessAuthSessionMetadataStore
import com.wzx.huitai.desktop.security.BusinessAuthRevocationMarkerPort
import com.wzx.huitai.desktop.security.FileBusinessAuthRevocationMarkerStore
import com.wzx.huitai.desktop.security.JceksAuthCredentialPersistence
import com.wzx.huitai.desktop.security.LocalCredentialStoreUnavailableException
import com.wzx.huitai.desktop.security.RedundantBusinessAuthRevocationMarkerStore
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.integration.auth.AuthCredentialPersistencePort
import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.integration.identity.IdentityBoundaryActionPort
import com.wzx.huitai.integration.oa.auth.OaCandidateAccess
import com.wzx.huitai.integration.oa.auth.OaCandidateAuthenticationGateway
import com.wzx.huitai.integration.oa.auth.OaPermissionInfo
import com.wzx.huitai.integration.oa.auth.OaPermissionUser
import com.wzx.huitai.integration.oa.auth.OaPreAuthenticationGateway
import com.wzx.huitai.integration.oa.auth.OaTenantCandidate
import com.wzx.huitai.integration.oa.auth.OaTokenBundle
import com.wzx.huitai.security.secret.JceksSecretStore
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessAuthenticationOrchestratorTest {
    @Test
    fun `login follows verifying authenticating registering ready and installs registry only after commit`() = runTest {
        val fixture = Fixture()
        assertEquals(listOf(fixture.candidate), fixture.orchestrator.findTenantCandidates("lawyer@example.com"))
        assertEquals(BusinessAccessGateState.VERIFYING, fixture.orchestrator.gate.value)
        fixture.orchestrator.enterTenantSelection()
        assertEquals(BusinessAccessGateState.SELECTING_TENANT, fixture.orchestrator.gate.value)
        fixture.preAuth.onLogin = {
            assertEquals(BusinessAccessGateState.AUTHENTICATING, fixture.orchestrator.gate.value)
        }
        fixture.registration.onCommit = {
            assertNull(fixture.registry.currentIdentity())
            assertEquals(BusinessAccessGateState.REGISTERING_AGENT, fixture.orchestrator.gate.value)
        }
        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        runCurrent()

        assertEquals(BusinessAccessGateState.READY, fixture.orchestrator.gate.value)
        assertEquals("user-1", fixture.registry.currentIdentity()?.userId)
        assertEquals(listOf("prepare", "identity", "catalog", "context", "commit"), fixture.registration.calls)
    }

    @Test
    fun `candidate token permission and configured platform must agree before local persistence`() = runTest {
        val cases = listOf<(Fixture) -> Unit>(
            { it.preAuth.token = token(userId = "other-user") },
            { it.candidateGateway.permission = permission(userId = "other-user") },
            { it.candidate = it.candidate.copy(platformId = 200) },
        )
        cases.forEach { mutate ->
            val fixture = Fixture()
            mutate(fixture)
            fixture.verify()
            val failure = assertFailsWith<BusinessAuthenticationException> {
                fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
            }
            assertEquals(BusinessLoginErrorCode.PERMISSION_LOAD_FAILED, failure.code)
            assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
            assertNull(fixture.credentials.tokens)
            assertNull(fixture.metadata.value)
            assertNull(fixture.registry.currentIdentity())
            assertEquals(1, fixture.candidateGateway.logoutCount)
        }
    }

    @Test
    fun `every registration partial failure rolls back signed out workspace and local authentication`() = runTest {
        RegistrationFailureStage.entries.forEach { stage ->
            val fixture = Fixture()
            fixture.registration.failureStage = stage
            fixture.verify()
            val failure = assertFailsWith<BusinessAuthenticationException>(stage.name) {
                fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
            }
            assertEquals(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED, failure.code, stage.name)
            if (stage != RegistrationFailureStage.PREPARE) {
                assertTrue("rollback" in fixture.registration.calls, stage.name)
            }
            assertTrue("signed-out" in fixture.registration.calls, stage.name)
            assertTrue("clear-workspace" in fixture.registration.calls, stage.name)
            assertNull(fixture.registry.currentIdentity(), stage.name)
            assertNull(fixture.credentials.tokens, stage.name)
            assertNull(fixture.metadata.value, stage.name)
            assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value, stage.name)
        }
    }

    @Test
    fun `metadata and token persistence failures compensate without exposing primary secrets`() = runTest {
        val metadataFailure = Fixture().apply { metadata.failSave = true }
        metadataFailure.verify()
        val first = assertFailsWith<BusinessAuthenticationException> {
            metadataFailure.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), metadataFailure.candidate)
        }
        assertEquals(BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED, first.code)
        assertNull(metadataFailure.credentials.tokens)
        assertNull(metadataFailure.metadata.value)
        assertEquals(1, metadataFailure.candidateGateway.logoutCount)

        val tokenFailure = Fixture().apply { credentials.failReplace = true }
        tokenFailure.verify()
        val second = assertFailsWith<BusinessAuthenticationException> {
            tokenFailure.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), tokenFailure.candidate)
        }
        assertEquals(BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED, second.code)
        assertNull(tokenFailure.metadata.value)
        assertNull(tokenFailure.registry.currentIdentity())
        assertFalse(second.toString().contains("access-secret"))
        assertFalse(second.toString().contains("refresh-secret"))
    }

    @Test
    fun `restore with neither credential signs out while either half or invalid entry clears both`() = runTest {
        val empty = Fixture()
        empty.orchestrator.restore()
        assertEquals(BusinessAccessGateState.SIGNED_OUT, empty.orchestrator.gate.value)
        assertTrue(empty.preAuth.refreshCalls.isEmpty())

        val halves = listOf(
            Fixture().apply { credentials.tokens = AuthTokenSet("old-access", "old-refresh") },
            Fixture().apply { metadata.value = metadata() },
            Fixture().apply {
                credentials.tokens = AuthTokenSet("old-access", "old-refresh")
                metadata.loadFailure = IllegalStateException("invalid local entry")
            },
        )
        halves.forEach { fixture ->
            fixture.orchestrator.restore()
            assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
            assertNull(fixture.credentials.tokens)
            assertNull(fixture.metadata.value)
            assertTrue(fixture.preAuth.refreshCalls.isEmpty())
        }
    }

    @Test
    fun `restore rotates refresh token reloads permission and uses normal registration transaction`() = runTest {
        val fixture = Fixture().apply {
            credentials.tokens = AuthTokenSet("old-access", "old-refresh")
            metadata.value = metadata()
            preAuth.token = token(access = "rotated-access", refresh = "rotated-refresh")
        }

        fixture.orchestrator.restore()

        assertEquals(listOf("tenant-1" to "old-refresh"), fixture.preAuth.refreshCalls)
        assertEquals("rotated-access", fixture.candidateGateway.loaded.single().accessToken)
        assertEquals("rotated-refresh", fixture.credentials.tokens?.refreshToken)
        assertEquals(BusinessAccessGateState.READY, fixture.orchestrator.gate.value)
        assertEquals(listOf("prepare", "identity", "catalog", "context", "commit"), fixture.registration.calls)
    }

    @Test
    fun `restore refresh permission or registration failure clears pair and requires login`() = runTest {
        val fixtures = listOf(
            Fixture().apply { preAuth.failure = IllegalStateException("refresh body secret") },
            Fixture().apply { candidateGateway.failure = IllegalStateException("permission body secret") },
            Fixture().apply { registration.failureStage = RegistrationFailureStage.CONTEXT },
        )
        fixtures.forEach { fixture ->
            fixture.credentials.tokens = AuthTokenSet("old-access", "old-refresh")
            fixture.metadata.value = metadata()
            fixture.orchestrator.restore()
            assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
            assertNull(fixture.credentials.tokens)
            assertNull(fixture.metadata.value)
            assertNull(fixture.registry.currentIdentity())
        }
    }

    @Test
    fun `restore propagates cancellation from credential loading and remote refresh`() = runTest {
        val loadCancelled = Fixture().apply {
            credentials.loadFailure = CancellationException("cancelled-sensitive")
        }
        assertFailsWith<CancellationException> { loadCancelled.orchestrator.restore() }

        val refreshCancelled = Fixture().apply {
            credentials.tokens = AuthTokenSet("old-access", "old-refresh")
            metadata.value = metadata()
            preAuth.failure = CancellationException("cancelled-sensitive")
        }
        assertFailsWith<CancellationException> { refreshCancelled.orchestrator.restore() }
    }

    @Test
    fun `restore preserves primary stable remote error after compensation`() = runTest {
        val fixture = Fixture().apply {
            credentials.tokens = AuthTokenSet("old-access", "old-refresh")
            metadata.value = metadata()
            preAuth.failure = IllegalStateException("remote-sensitive-body")
        }

        fixture.orchestrator.restore()

        assertEquals(BusinessLoginErrorCode.REMOTE_UNAVAILABLE, fixture.orchestrator.lastError.value?.code)
        assertFalse(fixture.orchestrator.lastError.value.toString().contains("remote-sensitive-body"))
    }

    @Test
    fun `logout immediately invalidates registry then revokes full old scope and remote failure cannot block local sign out`() = runTest {
        val fixture = Fixture()
        fixture.verify()
        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        val oldIdentity = requireNotNull(fixture.registry.currentIdentity())
        fixture.candidateGateway.logoutFailure = IllegalStateException("remote token body")

        fixture.orchestrator.logout()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.registry.currentIdentity())
        assertEquals(oldIdentity.actionScope(), fixture.actions.cancelledScope)
        assertEquals(
            setOf(ActionExecutionState.RECEIVED, ActionExecutionState.VALIDATING, ActionExecutionState.PREVIEWED, ActionExecutionState.WAITING_APPROVAL),
            fixture.actions.cancelledStates,
        )
        assertEquals(oldIdentity.actionScope(), fixture.actions.detachedScope)
        assertTrue("signed-out" in fixture.registration.calls)
        assertTrue("clear-workspace" in fixture.registration.calls)
        assertNull(fixture.credentials.tokens)
        assertNull(fixture.metadata.value)
    }

    @Test
    fun `concurrent logout and expiry share one revocation and keep login closed until cleanup finishes`() = runTest {
        val fixture = Fixture().apply {
            registration.publishSignedOutStarted = CompletableDeferred()
            registration.publishSignedOutRelease = CompletableDeferred()
            registration.remainingGatedPublishSignedOutCalls = 1
        }
        fixture.verify()
        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)

        val logout = async { fixture.orchestrator.logout() }
        fixture.registration.publishSignedOutStarted!!.await()
        val expiry = async { fixture.orchestrator.onAuthenticationExpired() }
        try {
            runCurrent()
            assertFalse(expiry.isCompleted, "a concurrent revocation must wait for the owner")
            assertEquals(BusinessAccessGateState.SIGNING_OUT, fixture.orchestrator.gate.value)
            val rejected = assertFailsWith<BusinessAuthenticationException> {
                fixture.orchestrator.findTenantCandidates("lawyer@example.com")
            }
            assertEquals(BusinessLoginErrorCode.AUTHENTICATION_IN_PROGRESS, rejected.code)
        } finally {
            fixture.registration.publishSignedOutRelease!!.complete(Unit)
        }
        logout.await()
        expiry.await()

        assertEquals(1, fixture.revocationMarker.markCount)
        assertEquals(1, fixture.credentials.clearCount)
        assertEquals(1, fixture.metadata.clearCount)
        assertEquals(1, fixture.actions.cancelCount)
        assertEquals(1, fixture.actions.detachCount)
        assertEquals(1, fixture.registration.calls.count { it == "signed-out" })
        assertEquals(1, fixture.registration.calls.count { it == "clear-workspace" })
        assertEquals(1, fixture.candidateGateway.logoutCount)

        fixture.verify()
        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        runCurrent()
        assertEquals(BusinessAccessGateState.READY, fixture.orchestrator.gate.value)
        assertEquals("user-1", fixture.registry.currentIdentity()?.userId)
        assertEquals(1, fixture.registration.calls.count { it == "signed-out" })
        assertEquals(1, fixture.registration.calls.count { it == "clear-workspace" })
    }

    @Test
    fun `close owner coalesces concurrent logout instead of rejecting or duplicating cleanup`() = runTest {
        val fixture = Fixture().apply {
            registration.publishSignedOutStarted = CompletableDeferred()
            registration.publishSignedOutRelease = CompletableDeferred()
            registration.remainingGatedPublishSignedOutCalls = 1
        }
        fixture.verify()
        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)

        val close = async { fixture.orchestrator.close() }
        fixture.registration.publishSignedOutStarted!!.await()
        val logout = async { fixture.orchestrator.logout() }
        try {
            runCurrent()
            assertFalse(logout.isCompleted, "logout must join an active close revocation")
            assertEquals(BusinessAccessGateState.SIGNING_OUT, fixture.orchestrator.gate.value)
        } finally {
            fixture.registration.publishSignedOutRelease!!.complete(Unit)
        }
        close.await()
        logout.await()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertEquals(1, fixture.revocationMarker.markCount)
        assertEquals(1, fixture.credentials.clearCount)
        assertEquals(1, fixture.metadata.clearCount)
        assertEquals(1, fixture.actions.cancelCount)
        assertEquals(1, fixture.actions.detachCount)
        assertEquals(1, fixture.registration.calls.count { it == "signed-out" })
        assertEquals(1, fixture.registration.calls.count { it == "clear-workspace" })
        assertEquals(0, fixture.candidateGateway.logoutCount, "close owner keeps close remote-logout semantics")
    }

    @Test
    fun `coalesced revocation waiters receive the same stable durable failure`() = runTest {
        val fixture = Fixture().apply {
            registration.publishSignedOutStarted = CompletableDeferred()
            registration.publishSignedOutRelease = CompletableDeferred()
            registration.remainingGatedPublishSignedOutCalls = 1
        }
        fixture.verify()
        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        fixture.revocationMarker.failMark = true
        fixture.credentials.failClear = true
        fixture.metadata.failClear = true

        val owner = async {
            assertFailsWith<BusinessAuthenticationException> { fixture.orchestrator.logout() }
        }
        fixture.registration.publishSignedOutStarted!!.await()
        val waiter = async {
            assertFailsWith<BusinessAuthenticationException> { fixture.orchestrator.onAuthenticationExpired() }
        }
        runCurrent()
        assertFalse(waiter.isCompleted)
        fixture.registration.publishSignedOutRelease!!.complete(Unit)

        assertEquals(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE, owner.await().code)
        assertEquals(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE, waiter.await().code)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.registry.currentIdentity())
        assertNull(fixture.sessionManager.identity.value)
        assertEquals(1, fixture.revocationMarker.markCount)
        assertEquals(1, fixture.credentials.clearCount)
        assertEquals(1, fixture.metadata.clearCount)
        assertEquals(1, fixture.registration.calls.count { it == "signed-out" })
        assertEquals(1, fixture.registration.calls.count { it == "clear-workspace" })
    }

    @Test
    fun `authentication and membership expiry share revocation path but expose distinct stable errors`() = runTest {
        val authentication = Fixture()
        authentication.verify()
        authentication.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), authentication.candidate)
        authentication.orchestrator.onAuthenticationExpired()
        assertEquals(BusinessLoginErrorCode.AUTH_EXPIRED, authentication.orchestrator.lastError.value?.code)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, authentication.orchestrator.gate.value)

        val membership = Fixture()
        membership.verify()
        membership.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), membership.candidate)
        membership.orchestrator.onMembershipExpired()
        assertEquals(BusinessLoginErrorCode.MEMBERSHIP_EXPIRED, membership.orchestrator.lastError.value?.code)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, membership.orchestrator.gate.value)
    }

    @Test
    fun `logout generation prevents cancellation ignoring late login from installing registry or ready`() = runTest {
        val fixture = Fixture().apply {
            registration.commitStarted = CompletableDeferred()
            registration.commitRelease = CompletableDeferred()
            registration.ignoreCancellation = true
        }
        fixture.verify()
        val login = async {
            fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        }
        fixture.registration.commitStarted!!.await()

        fixture.orchestrator.logout()
        fixture.registration.commitRelease!!.complete(Unit)
        assertFailsWith<CancellationException> { login.await() }
        advanceUntilIdle()
        assertNull(fixture.registry.currentIdentity())
        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
    }

    @Test
    fun `lifecycle starts restore once and close cancels restore then performs idempotent local revocation`() = runTest {
        val fixture = Fixture().apply {
            credentials.tokens = AuthTokenSet("old-access", "old-refresh")
            metadata.value = metadata()
            preAuth.refreshStarted = CompletableDeferred()
            preAuth.refreshRelease = CompletableDeferred()
        }
        val lifecycle = BusinessAuthenticationLifecycle(fixture.orchestrator, backgroundScope)
        lifecycle.start()
        lifecycle.start()
        fixture.preAuth.refreshStarted!!.await()
        lifecycle.close()
        lifecycle.close()
        runCurrent()

        assertEquals(1, fixture.preAuth.refreshCalls.size)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.registry.currentIdentity())
        assertNull(fixture.credentials.tokens)
        assertNull(fixture.metadata.value)
    }

    @Test
    fun `authenticate requires an active verified account and candidate capability`() = runTest {
        val fixture = Fixture()
        val bypassPassword = "password8".toCharArray()
        val direct = assertFailsWith<BusinessAuthenticationException> {
            fixture.orchestrator.authenticate("lawyer@example.com", bypassPassword, fixture.candidate)
        }
        assertEquals(BusinessLoginErrorCode.AUTHENTICATION_IN_PROGRESS, direct.code)
        assertEquals(0, fixture.preAuth.loginCount)

        fixture.orchestrator.findTenantCandidates("lawyer@example.com")
        val wrongAccount = assertFailsWith<BusinessAuthenticationException> {
            fixture.orchestrator.authenticate("other@example.com", "password8".toCharArray(), fixture.candidate)
        }
        assertEquals(BusinessLoginErrorCode.AUTHENTICATION_IN_PROGRESS, wrongAccount.code)
        assertEquals(0, fixture.preAuth.loginCount)

        val forged = fixture.candidate.copy(tenantId = "tenant-forged")
        val wrongCandidate = assertFailsWith<BusinessAuthenticationException> {
            fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), forged)
        }
        assertEquals(BusinessLoginErrorCode.AUTHENTICATION_IN_PROGRESS, wrongCandidate.code)
        assertEquals(0, fixture.preAuth.loginCount)

        fixture.orchestrator.cancelTenantSelection()
        assertFailsWith<BusinessAuthenticationException> {
            fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        }
        assertEquals(0, fixture.preAuth.loginCount)
    }

    @Test
    fun `restore owns pair loading so concurrent login is rejected and close cancels the load`() = runTest {
        val fixture = Fixture().apply {
            credentials.tokens = AuthTokenSet("old-access", "old-refresh")
            metadata.value = metadata()
            credentials.loadStarted = CompletableDeferred()
            credentials.loadRelease = CompletableDeferred()
        }
        val restoring = async { fixture.orchestrator.restore() }
        fixture.credentials.loadStarted!!.await()

        assertEquals(BusinessAccessGateState.RESTORING, fixture.orchestrator.gate.value)
        val concurrent = assertFailsWith<BusinessAuthenticationException> {
            fixture.orchestrator.findTenantCandidates("lawyer@example.com")
        }
        assertEquals(BusinessLoginErrorCode.AUTHENTICATION_IN_PROGRESS, concurrent.code)

        fixture.orchestrator.close()
        fixture.credentials.loadRelease!!.complete(Unit)
        assertFailsWith<CancellationException> { restoring.await() }
        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
    }

    @Test
    fun `late compensation from login A cannot revoke replacement login B`() = runTest {
        val fixture = Fixture().apply {
            registration.commitStarted = CompletableDeferred()
            registration.commitRelease = CompletableDeferred()
            registration.remainingGatedCommitCalls = 1
            registration.ignoreCancellation = true
        }
        fixture.orchestrator.findTenantCandidates("lawyer@example.com")
        fixture.preAuth.token = token(access = "access-a", refresh = "refresh-a")
        val loginA = async {
            fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        }
        fixture.registration.commitStarted!!.await()

        fixture.orchestrator.logout()
        fixture.preAuth.token = token(access = "access-b", refresh = "refresh-b")
        fixture.orchestrator.findTenantCandidates("lawyer@example.com")
        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        val identityB = requireNotNull(fixture.registry.currentIdentity())

        fixture.registration.commitRelease!!.complete(Unit)
        assertFailsWith<CancellationException> { loginA.await() }
        advanceUntilIdle()

        assertEquals(BusinessAccessGateState.READY, fixture.orchestrator.gate.value)
        assertEquals(identityB, fixture.registry.currentIdentity())
        assertEquals(identityB.authSessionId, fixture.sessionManager.identity.value?.authSessionId)
        assertEquals("access-b", fixture.credentials.tokens?.accessToken)
        assertEquals(1, fixture.registration.calls.count { it == "signed-out" })
        assertEquals(1, fixture.registration.calls.count { it == "clear-workspace" })
        assertEquals(1, fixture.credentials.clearCount)
    }

    @Test
    fun `logout serializes with delayed metadata commit and leaves no stale local pair`() = runTest {
        val fixture = Fixture().apply {
            metadata.saveStarted = CompletableDeferred()
            metadata.saveRelease = CountDownLatch(1)
            metadata.clearObserved = CountDownLatch(1)
        }
        fixture.verify()
        val login = async(Dispatchers.Default) {
            fixture.orchestrator.authenticate(
                "lawyer@example.com",
                "password8".toCharArray(),
                fixture.candidate,
            )
        }
        fixture.metadata.saveStarted!!.await()

        val logout = async(Dispatchers.Default) { fixture.orchestrator.logout() }
        assertFalse(fixture.metadata.clearObserved!!.await(200, TimeUnit.MILLISECONDS))
        assertEquals(BusinessAccessGateState.SIGNING_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.registry.currentIdentity())
        assertNull(fixture.sessionManager.identity.value)
        assertNull(fixture.sessionManager.accessToken())
        fixture.metadata.saveRelease!!.countDown()
        assertFailsWith<CancellationException> { login.await() }
        logout.await()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.registry.currentIdentity())
        assertNull(fixture.sessionManager.identity.value)
        assertNull(fixture.credentials.tokens)
        assertNull(fixture.metadata.value)
    }

    @Test
    fun `cancellation completes suspending rollback in non cancellable cleanup`() = runTest {
        val fixture = Fixture().apply {
            registration.commitStarted = CompletableDeferred()
            registration.commitRelease = CompletableDeferred()
            registration.rollbackMustSuspend = true
        }
        fixture.orchestrator.findTenantCandidates("lawyer@example.com")
        val login = async {
            fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        }
        fixture.registration.commitStarted!!.await()

        login.cancel()
        fixture.registration.commitRelease!!.complete(Unit)
        assertFailsWith<CancellationException> { login.await() }

        assertTrue(fixture.registration.rollbackCompleted)
        assertTrue("signed-out" in fixture.registration.calls)
        assertNull(fixture.registry.currentIdentity())
    }

    @Test
    fun `registry generation rejection after registration commit compensates the whole provisional login`() = runTest {
        val fixture = Fixture()
        fixture.verify()
        fixture.registration.onCommit = {
            fixture.registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
        }

        assertFailsWith<CancellationException> {
            fixture.orchestrator.authenticate(
                "lawyer@example.com",
                "password8".toCharArray(),
                fixture.candidate,
            )
        }

        assertTrue("commit" in fixture.registration.calls)
        assertTrue("rollback" in fixture.registration.calls)
        assertTrue("signed-out" in fixture.registration.calls)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.registry.currentIdentity())
        assertNull(fixture.sessionManager.identity.value)
        assertNull(fixture.credentials.tokens)
        assertNull(fixture.metadata.value)
    }

    @Test
    fun `tenant selection transition is illegal until candidate verification succeeds`() {
        val fixture = Fixture()

        assertFailsWith<IllegalStateException> { fixture.orchestrator.enterTenantSelection() }
        assertEquals(BusinessAccessGateState.STARTING, fixture.orchestrator.gate.value)
    }

    @Test
    fun `remote candidate logout is bounded and cannot stall local revocation`() = runTest {
        val fixture = Fixture(remoteLogoutTimeoutMillis = 10).apply {
            candidateGateway.logoutNeverReturns = true
        }
        fixture.orchestrator.findTenantCandidates("lawyer@example.com")
        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)

        fixture.orchestrator.logout()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.registry.currentIdentity())
        assertNull(fixture.sessionManager.identity.value)
    }

    @Test
    fun `every suspending cleanup adapter is bounded`() = runTest {
        val fixture = Fixture(cleanupStepTimeoutMillis = 10).apply {
            registration.failureStage = RegistrationFailureStage.CONTEXT
            registration.rollbackNeverReturns = true
            registration.publishSignedOutNeverReturns = true
            registration.clearWorkspaceNeverReturns = true
            actions.cancelNeverReturns = true
            actions.detachNeverReturns = true
            candidateGateway.logoutNeverReturns = true
        }
        fixture.verify()

        val failure = assertFailsWith<BusinessAuthenticationException> {
            fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        }

        assertEquals(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED, failure.code)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.registry.currentIdentity())
        assertNull(fixture.sessionManager.identity.value)
        assertEquals(1, fixture.actions.cancelCount)
        assertEquals(1, fixture.actions.detachCount)
        assertEquals(1, fixture.candidateGateway.logoutCount)
        assertTrue("rollback" in fixture.registration.calls)
        assertTrue("signed-out" in fixture.registration.calls)
        assertTrue("clear-workspace" in fixture.registration.calls)
    }

    @Test
    fun `durable clear failure keeps gate signed out and fail closes in memory authority`() = runTest {
        val fixture = Fixture()
        fixture.orchestrator.findTenantCandidates("lawyer@example.com")
        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        fixture.credentials.failClear = true
        fixture.metadata.failClear = true

        fixture.orchestrator.logout()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.registry.currentIdentity())
        assertNull(fixture.sessionManager.identity.value)
        assertEquals(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE, fixture.orchestrator.lastError.value?.code)
        assertTrue(fixture.revocationMarker.revoked)
    }

    @Test
    fun `revoke returns normally when both local credential records clear despite marker failure`() = runTest {
        val fixture = Fixture()
        fixture.verify()
        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        fixture.revocationMarker.failMark = true

        fixture.orchestrator.logout()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.credentials.tokens)
        assertNull(fixture.metadata.value)
    }

    @Test
    fun `revoke cannot return successfully without revoked marker or complete local pair deletion`() = runTest {
        val fixture = Fixture()
        fixture.verify()
        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        fixture.revocationMarker.failMark = true
        fixture.credentials.failClear = true
        fixture.metadata.failClear = true

        val failure = assertFailsWith<BusinessAuthenticationException> { fixture.orchestrator.logout() }

        assertEquals(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE, failure.code)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.sessionManager.identity.value)
        assertNull(fixture.registry.currentIdentity())
    }

    @Test
    fun `fallback revocation marker survives primary and JCEKS clear failures across restart`() = runTest {
        val root = Files.createTempDirectory("orchestrator-revocation-restart")
        val keyStorePath = root.resolve("credentials.jceks")
        val markerPath = root.resolve("auth-revoked-v1")
        val password = "password".toCharArray()
        val fallbackMarker = FileBusinessAuthRevocationMarkerStore(markerPath)
        val marker = RedundantBusinessAuthRevocationMarkerStore(
            primary = MarkFailingRevocationMarker(),
            fallback = fallbackMarker,
        )
        JceksSecretStore(keyStorePath, password.copyOf()).use { secrets ->
            val tokenDelegate = JceksAuthCredentialPersistence(secrets)
            val metadataDelegate = BusinessAuthSessionMetadataStore(secrets)
            val tokenPersistence = ClearFailingCredentialPersistence(tokenDelegate)
            val metadataPersistence = ClearFailingMetadataPersistence(metadataDelegate)
            val preAuth = FakePreAuthenticationGateway()
            val orchestrator = BusinessAuthenticationOrchestrator(
                preAuthentication = preAuth,
                candidateAuthentication = FakeCandidateGateway(),
                credentialPersistence = tokenPersistence,
                authSessionManager = AuthSessionManager(tokenPersistence),
                metadataPersistence = metadataPersistence,
                revocationMarker = marker,
                registration = FakeRegistrationPort(),
                identityRegistry = BusinessIdentityRegistry(),
                actions = FakeActions(),
                desktopInstanceId = "desktop-instance-1",
                desktopSessionId = "desktop-session-1",
                platformId = 100,
            )
            orchestrator.findTenantCandidates("lawyer@example.com")
            orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), candidate())

            orchestrator.logout()

            assertEquals(BusinessAccessGateState.SIGNED_OUT, orchestrator.gate.value)
            assertTrue(fallbackMarker.isRevoked())
            assertEquals("refresh-secret", tokenDelegate.load()?.refreshToken)
            assertEquals("user-1", metadataDelegate.load()?.userId)
        }

        val restartedPreAuth = FakePreAuthenticationGateway()
        JceksSecretStore(keyStorePath, password.copyOf()).use { reopened ->
            val tokenPersistence = JceksAuthCredentialPersistence(reopened)
            val metadataPersistence = StoredBusinessAuthSessionMetadataPort(BusinessAuthSessionMetadataStore(reopened))
            val orchestrator = BusinessAuthenticationOrchestrator(
                preAuthentication = restartedPreAuth,
                candidateAuthentication = FakeCandidateGateway(),
                credentialPersistence = tokenPersistence,
                authSessionManager = AuthSessionManager(tokenPersistence),
                metadataPersistence = metadataPersistence,
                revocationMarker = RedundantBusinessAuthRevocationMarkerStore(
                    primary = FakeRevocationMarker(),
                    fallback = FileBusinessAuthRevocationMarkerStore(markerPath),
                ),
                registration = FakeRegistrationPort(),
                identityRegistry = BusinessIdentityRegistry(),
                actions = FakeActions(),
                desktopInstanceId = "desktop-instance-2",
                desktopSessionId = "desktop-session-2",
                platformId = 100,
            )

            orchestrator.restore()

            assertEquals(BusinessAccessGateState.SIGNED_OUT, orchestrator.gate.value)
            assertTrue(restartedPreAuth.refreshCalls.isEmpty())
        }
        password.fill('\u0000')
    }

    @Test
    fun `only a completed explicit login clears an existing revocation marker`() = runTest {
        val fixture = Fixture().apply { revocationMarker.revoked = true }
        fixture.verify()

        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)

        assertEquals(BusinessAccessGateState.READY, fixture.orchestrator.gate.value)
        assertFalse(fixture.revocationMarker.revoked)
    }

    @Test
    fun `explicit login durably authorizes both records before publishing ready identity`() = runTest {
        val fixture = Fixture().apply {
            revocationMarker.revoked = true
            revocationMarker.onClear = {
                assertEquals(BusinessAccessGateState.REGISTERING_AGENT, orchestrator.gate.value)
                assertNull(registry.currentIdentity())
            }
        }
        fixture.verify()

        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)

        assertEquals(BusinessAccessGateState.READY, fixture.orchestrator.gate.value)
        assertFalse(fixture.revocationMarker.revoked)
    }

    @Test
    fun `authorization record failure never publishes registry and signs out`() = runTest {
        val fixture = Fixture().apply {
            revocationMarker.revoked = true
            revocationMarker.failClear = true
            revocationMarker.onClear = {
                assertEquals(BusinessAccessGateState.REGISTERING_AGENT, orchestrator.gate.value)
                assertNull(registry.currentIdentity())
            }
        }
        fixture.verify()

        val failure = assertFailsWith<BusinessAuthenticationException> {
            fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        }

        assertEquals(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE, failure.code)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.registry.currentIdentity())
        assertNull(fixture.sessionManager.identity.value)
        assertNull(fixture.credentials.tokens)
        assertNull(fixture.metadata.value)
        assertTrue(fixture.revocationMarker.revoked)
        assertTrue("rollback" in fixture.registration.calls)
    }

    @Test
    fun `durably authorized credentials after a pre publish crash restore by redoing Agent registration`() = runTest {
        val fixture = Fixture().apply {
            credentials.tokens = AuthTokenSet("old-access", "old-refresh")
            metadata.value = metadata()
            revocationMarker.revoked = false
        }

        fixture.orchestrator.restore()

        assertEquals(listOf("tenant-1" to "old-refresh"), fixture.preAuth.refreshCalls)
        assertEquals(listOf("prepare", "identity", "catalog", "context", "commit"), fixture.registration.calls)
        assertEquals(BusinessAccessGateState.READY, fixture.orchestrator.gate.value)
        assertEquals("user-1", fixture.registry.currentIdentity()?.userId)
    }

    @Test
    fun `revoke racing durable authorize prevents any ready publication and leaves revoked state`() = runTest {
        val fixture = Fixture().apply {
            revocationMarker.revoked = true
            revocationMarker.clearStarted = CountDownLatch(1)
            revocationMarker.clearRelease = CountDownLatch(1)
        }
        fixture.verify()
        val observed = java.util.Collections.synchronizedList(mutableListOf<BusinessIdentityRegistrySnapshot>())
        val collector = backgroundScope.launch { fixture.registry.snapshot.collect { observed += it } }
        val login = async(Dispatchers.Default) {
            fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        }
        assertTrue(fixture.revocationMarker.clearStarted!!.await(2, TimeUnit.SECONDS))
        assertNull(fixture.registry.currentIdentity())

        val logout = async(Dispatchers.Default) { fixture.orchestrator.logout() }
        fixture.orchestrator.gate.first { it == BusinessAccessGateState.SIGNING_OUT }
        fixture.revocationMarker.clearRelease!!.countDown()
        assertFailsWith<CancellationException> { login.await() }
        logout.await()
        collector.cancel()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.registry.currentIdentity())
        assertTrue(fixture.revocationMarker.revoked)
        assertTrue(observed.none { it.gate == BusinessAccessGateState.READY })
    }

    @Test
    fun `compensation blocks owned authority before any suspending rollback`() = runTest {
        val fixture = Fixture().apply {
            registration.failureStage = RegistrationFailureStage.CONTEXT
            registration.rollbackStarted = CompletableDeferred()
            registration.rollbackRelease = CompletableDeferred()
        }
        fixture.verify()

        val login = async(Dispatchers.Default) {
            assertFailsWith<BusinessAuthenticationException> {
                fixture.orchestrator.authenticate(
                    "lawyer@example.com",
                    "password8".toCharArray(),
                    fixture.candidate,
                )
            }
        }
        fixture.registration.rollbackStarted!!.await()

        assertNull(fixture.registry.currentIdentity())
        assertNull(fixture.sessionManager.identity.value)
        assertNull(fixture.sessionManager.accessToken())

        fixture.registration.rollbackRelease!!.complete(Unit)
        login.await()
        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
    }

    @Test
    fun `adapter cancellation exceptions cannot abort mandatory compensation cleanup`() = runTest {
        val fixture = Fixture().apply {
            registration.failureStage = RegistrationFailureStage.CONTEXT
            registration.rollbackThrowsCancellation = true
            registration.publishSignedOutThrowsCancellation = true
            registration.clearWorkspaceThrowsCancellation = true
            candidateGateway.logoutThrowsCancellation = true
            actions.cancelThrowsCancellation = true
            actions.detachThrowsCancellation = true
        }
        fixture.verify()

        val failure = assertFailsWith<BusinessAuthenticationException> {
            fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        }

        assertEquals(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED, failure.code)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.sessionManager.identity.value)
        assertNull(fixture.credentials.tokens)
        assertNull(fixture.metadata.value)
        assertEquals(1, fixture.candidateGateway.logoutCount)
        assertEquals(1, fixture.actions.cancelCount)
        assertEquals(1, fixture.actions.detachCount)
        assertTrue("rollback" in fixture.registration.calls)
        assertTrue("signed-out" in fixture.registration.calls)
        assertTrue("clear-workspace" in fixture.registration.calls)
    }

    @Test
    fun `adapter child context cancellation cannot abort mandatory compensation cleanup`() = runTest {
        val fixture = Fixture().apply {
            registration.failureStage = RegistrationFailureStage.CONTEXT
            registration.rollbackCancelsContext = true
            registration.publishSignedOutCancelsContext = true
            registration.clearWorkspaceCancelsContext = true
            candidateGateway.logoutCancelsContext = true
            actions.cancelCancelsContext = true
            actions.detachCancelsContext = true
        }
        fixture.verify()

        val failure = assertFailsWith<BusinessAuthenticationException> {
            fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)
        }

        assertEquals(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED, failure.code)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertNull(fixture.sessionManager.identity.value)
        assertNull(fixture.credentials.tokens)
        assertNull(fixture.metadata.value)
        assertEquals(1, fixture.candidateGateway.logoutCount)
        assertEquals(1, fixture.actions.cancelCount)
        assertEquals(1, fixture.actions.detachCount)
        assertTrue("rollback" in fixture.registration.calls)
        assertTrue("signed-out" in fixture.registration.calls)
        assertTrue("clear-workspace" in fixture.registration.calls)
    }

    @Test
    fun `local remembered credential failure revokes ready registry and in memory authentication`() = runTest {
        val fixture = Fixture()
        fixture.verify()
        fixture.orchestrator.authenticate("lawyer@example.com", "password8".toCharArray(), fixture.candidate)

        fixture.orchestrator.onLocalCredentialStoreFailure(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE)

        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertEquals(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE, fixture.orchestrator.lastError.value?.code)
        assertNull(fixture.registry.currentIdentity())
        assertNull(fixture.sessionManager.identity.value)
        assertNull(fixture.credentials.tokens)
        assertNull(fixture.metadata.value)
        assertTrue("signed-out" in fixture.registration.calls)
        assertTrue("clear-workspace" in fixture.registration.calls)
        assertEquals(1, fixture.candidateGateway.logoutCount)
    }

    @Test
    fun `restore classifies unavailable shared store without attempting destructive cleanup`() = runTest {
        val fixture = Fixture().apply {
            metadata.loadFailure = LocalCredentialStoreUnavailableException()
        }

        fixture.orchestrator.restore()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, fixture.orchestrator.gate.value)
        assertEquals(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE, fixture.orchestrator.lastError.value?.code)
        assertEquals(0, fixture.credentials.clearCount)
        assertEquals(0, fixture.metadata.clearCount)
    }

    @Test
    fun `restore maps a closed production token store to local keystore unavailable`() = runTest {
        val password = "password".toCharArray()
        val store = JceksSecretStore(
            Files.createTempDirectory("orchestrator-closed-token-store").resolve("credentials.jceks"),
            password,
        )
        val persistence = JceksAuthCredentialPersistence(store)
        store.close()
        password.fill('\u0000')
        val metadata = FakeMetadataPort()
        val orchestrator = BusinessAuthenticationOrchestrator(
            preAuthentication = FakePreAuthenticationGateway(),
            candidateAuthentication = FakeCandidateGateway(),
            credentialPersistence = persistence,
            authSessionManager = AuthSessionManager(persistence),
            metadataPersistence = metadata,
            revocationMarker = FakeRevocationMarker(),
            registration = FakeRegistrationPort(),
            identityRegistry = BusinessIdentityRegistry(),
            actions = FakeActions(),
            desktopInstanceId = "desktop-instance-1",
            desktopSessionId = "desktop-session-1",
            platformId = 100,
        )

        orchestrator.restore()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, orchestrator.gate.value)
        assertEquals(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE, orchestrator.lastError.value?.code)
        assertEquals(0, metadata.clearCount)
    }

    private class Fixture(
        remoteLogoutTimeoutMillis: Long = 2_000,
        cleanupStepTimeoutMillis: Long = 2_000,
        val revocationMarker: FakeRevocationMarker = FakeRevocationMarker(),
    ) {
        var candidate = candidate()
        val preAuth = FakePreAuthenticationGateway()
        val candidateGateway = FakeCandidateGateway()
        val credentials = FakeCredentialPersistence()
        val metadata = FakeMetadataPort()
        val registration = FakeRegistrationPort()
        val registry = BusinessIdentityRegistry()
        val actions = FakeActions()
        private var authSessionSequence = 0
        val sessionManager = AuthSessionManager(credentials, authSessionIdFactory = { "auth-session-${++authSessionSequence}" })
        val orchestrator = BusinessAuthenticationOrchestrator(
            preAuthentication = preAuth,
            candidateAuthentication = candidateGateway,
            credentialPersistence = credentials,
            authSessionManager = sessionManager,
            metadataPersistence = metadata,
            revocationMarker = revocationMarker,
            registration = registration,
            identityRegistry = registry,
            actions = actions,
            desktopInstanceId = "desktop-instance-1",
            desktopSessionId = "desktop-session-1",
            platformId = 100,
            now = { Instant.parse("2026-07-22T00:00:00Z") },
            remoteLogoutTimeoutMillis = remoteLogoutTimeoutMillis,
            cleanupStepTimeoutMillis = cleanupStepTimeoutMillis,
        )

        suspend fun verify(account: String = "lawyer@example.com") {
            preAuth.candidates = listOf(candidate)
            orchestrator.findTenantCandidates(account)
        }
    }

    private class FakeRevocationMarker : BusinessAuthRevocationMarkerPort {
        var revoked = false
        var markCount = 0
        var failMark = false
        var failClear = false
        var onClear: (() -> Unit)? = null
        var clearStarted: CountDownLatch? = null
        var clearRelease: CountDownLatch? = null
        override fun isRevoked(): Boolean = revoked
        override fun markRevoked() {
            markCount += 1
            if (failMark) error("marker revoke failed")
            revoked = true
        }
        override fun clearAfterExplicitLogin() {
            onClear?.invoke()
            clearStarted?.countDown()
            clearRelease?.await()
            if (failClear) error("marker clear failed")
            revoked = false
        }
    }

    private class MarkFailingRevocationMarker : BusinessAuthRevocationMarkerPort {
        override fun isRevoked(): Boolean = false
        override fun markRevoked(): Unit = error("primary marker unavailable")
        override fun clearAfterExplicitLogin() = Unit
    }

    private class ClearFailingCredentialPersistence(
        private val delegate: AuthCredentialPersistencePort,
    ) : AuthCredentialPersistencePort {
        override suspend fun load(): AuthTokenSet? = delegate.load()
        override suspend fun replace(tokens: AuthTokenSet) = delegate.replace(tokens)
        override suspend fun clear(): Unit = error("simulated token clear failure")
    }

    private class ClearFailingMetadataPersistence(
        private val delegate: BusinessAuthSessionMetadataStore,
    ) : BusinessAuthSessionMetadataPersistencePort {
        override fun load(): BusinessAuthSessionMetadata? = delegate.load()
        override fun saveOrReplace(metadata: BusinessAuthSessionMetadata) = delegate.saveOrReplace(metadata)
        override fun clear(): Unit = error("simulated metadata clear failure")
    }

    private class FakePreAuthenticationGateway : OaPreAuthenticationGateway {
        var candidates = listOf(candidate())
        var token = token()
        var failure: Throwable? = null
        var onLogin: (() -> Unit)? = null
        var loginCount = 0
        val refreshCalls = mutableListOf<Pair<String, String>>()
        var refreshStarted: CompletableDeferred<Unit>? = null
        var refreshRelease: CompletableDeferred<Unit>? = null

        override suspend fun findTenantCandidates(mobile: String): List<OaTenantCandidate> {
            failure?.let { throw it }
            return candidates
        }

        override suspend fun login(mobileOrEmail: String, password: CharArray, tenantId: String): OaTokenBundle {
            loginCount += 1
            onLogin?.invoke()
            failure?.let { throw it }
            return token
        }

        override suspend fun refresh(tenantId: String, refreshToken: String): OaTokenBundle {
            refreshCalls += tenantId to refreshToken
            refreshStarted?.complete(Unit)
            refreshRelease?.await()
            failure?.let { throw it }
            return token
        }
    }

    private class FakeCandidateGateway : OaCandidateAuthenticationGateway {
        var permission = permission()
        var failure: Throwable? = null
        var logoutFailure: Throwable? = null
        var logoutNeverReturns = false
        var logoutThrowsCancellation = false
        var logoutCancelsContext = false
        val loaded = mutableListOf<OaCandidateAccess>()
        var logoutCount = 0

        override suspend fun loadPermissionInfo(candidate: OaCandidateAccess): OaPermissionInfo {
            loaded += candidate
            failure?.let { throw it }
            return permission
        }

        override suspend fun logout(candidate: OaCandidateAccess) {
            logoutCount += 1
            if (logoutNeverReturns) CompletableDeferred<Unit>().await()
            if (logoutThrowsCancellation) throw CancellationException("adapter self-cancelled")
            if (logoutCancelsContext) {
                currentCoroutineContext().cancel(CancellationException("adapter cancelled child context"))
                yield()
            }
            logoutFailure?.let { throw it }
        }
    }

    private class FakeCredentialPersistence : AuthCredentialPersistencePort {
        var tokens: AuthTokenSet? = null
        var failLoad = false
        var loadFailure: Throwable? = null
        var failReplace = false
        var failClear = false
        var clearCount = 0
        var loadStarted: CompletableDeferred<Unit>? = null
        var loadRelease: CompletableDeferred<Unit>? = null

        override suspend fun load(): AuthTokenSet? {
            loadStarted?.complete(Unit)
            loadRelease?.await()
            loadFailure?.let { throw it }
            if (failLoad) error("invalid token entry")
            return tokens
        }

        override suspend fun replace(tokens: AuthTokenSet) {
            if (failReplace) error("token persistence failed")
            this.tokens = tokens
        }

        override suspend fun clear() {
            clearCount += 1
            if (failClear) error("token clear failed")
            tokens = null
        }
    }

    private class FakeMetadataPort : BusinessAuthSessionMetadataPersistencePort {
        var value: BusinessAuthSessionMetadata? = null
        var loadFailure: Throwable? = null
        var failSave = false
        var failClear = false
        var clearCount = 0
        var saveStarted: CompletableDeferred<Unit>? = null
        var saveRelease: CountDownLatch? = null
        var clearObserved: CountDownLatch? = null

        override fun load(): BusinessAuthSessionMetadata? {
            loadFailure?.let { throw it }
            return value
        }

        override fun saveOrReplace(metadata: BusinessAuthSessionMetadata) {
            saveStarted?.complete(Unit)
            saveRelease?.await()
            value = metadata
            if (failSave) error("metadata persistence failed")
        }

        override fun clear() {
            clearCount += 1
            if (failClear) error("metadata clear failed")
            value = null
            clearObserved?.countDown()
        }
    }

    private class FakeRegistrationPort : BusinessAgentRegistrationTransactionPort {
        val calls = mutableListOf<String>()
        var failureStage: RegistrationFailureStage? = null
        var onCommit: (() -> Unit)? = null
        var commitStarted: CompletableDeferred<Unit>? = null
        var commitRelease: CompletableDeferred<Unit>? = null
        var ignoreCancellation = false
        var remainingGatedCommitCalls: Int? = null
        var rollbackMustSuspend = false
        var rollbackNeverReturns = false
        var rollbackStarted: CompletableDeferred<Unit>? = null
        var rollbackRelease: CompletableDeferred<Unit>? = null
        var rollbackThrowsCancellation = false
        var rollbackCancelsContext = false
        var publishSignedOutNeverReturns = false
        var publishSignedOutStarted: CompletableDeferred<Unit>? = null
        var publishSignedOutRelease: CompletableDeferred<Unit>? = null
        var remainingGatedPublishSignedOutCalls: Int? = null
        var publishSignedOutThrowsCancellation = false
        var publishSignedOutCancelsContext = false
        var clearWorkspaceNeverReturns = false
        var clearWorkspaceThrowsCancellation = false
        var clearWorkspaceCancelsContext = false
        var rollbackCompleted = false

        override suspend fun prepare(identity: BusinessIdentity): BusinessAgentRegistrationTransaction {
            calls += "prepare"
            fail(RegistrationFailureStage.PREPARE)
            return object : BusinessAgentRegistrationTransaction {
                override suspend fun registerIdentity() { calls += "identity"; fail(RegistrationFailureStage.IDENTITY) }
                override suspend fun registerCapabilityCatalog() { calls += "catalog"; fail(RegistrationFailureStage.CATALOG) }
                override suspend fun registerInitialContext() { calls += "context"; fail(RegistrationFailureStage.CONTEXT) }
                override suspend fun commit() {
                    calls += "commit"
                    val remaining = remainingGatedCommitCalls
                    val gated = commitRelease != null && (remaining == null || remaining > 0)
                    if (remaining != null && remaining > 0) remainingGatedCommitCalls = remaining - 1
                    if (gated) {
                        commitStarted?.complete(Unit)
                        try {
                            commitRelease?.await()
                        } catch (cancelled: CancellationException) {
                            if (!ignoreCancellation) throw cancelled
                            withContext(NonCancellable) { commitRelease?.await() }
                            throw cancelled
                        }
                    }
                    fail(RegistrationFailureStage.COMMIT)
                    onCommit?.invoke()
                }
                override suspend fun rollback() {
                    calls += "rollback"
                    rollbackStarted?.complete(Unit)
                    rollbackRelease?.await()
                    if (rollbackNeverReturns) CompletableDeferred<Unit>().await()
                    if (rollbackThrowsCancellation) throw CancellationException("adapter self-cancelled")
                    if (rollbackCancelsContext) {
                        currentCoroutineContext().cancel(CancellationException("adapter cancelled child context"))
                        yield()
                    }
                    if (rollbackMustSuspend) yield()
                    rollbackCompleted = true
                }
            }
        }

        override suspend fun publishSignedOut() {
            calls += "signed-out"
            val remaining = remainingGatedPublishSignedOutCalls
            val gated = publishSignedOutRelease != null && (remaining == null || remaining > 0)
            if (remaining != null && remaining > 0) remainingGatedPublishSignedOutCalls = remaining - 1
            if (gated) {
                publishSignedOutStarted?.complete(Unit)
                publishSignedOutRelease?.await()
            }
            if (publishSignedOutNeverReturns) CompletableDeferred<Unit>().await()
            if (publishSignedOutThrowsCancellation) throw CancellationException("adapter self-cancelled")
            if (publishSignedOutCancelsContext) {
                currentCoroutineContext().cancel(CancellationException("adapter cancelled child context"))
                yield()
            }
        }
        override suspend fun clearWorkspace() {
            calls += "clear-workspace"
            if (clearWorkspaceNeverReturns) CompletableDeferred<Unit>().await()
            if (clearWorkspaceThrowsCancellation) throw CancellationException("adapter self-cancelled")
            if (clearWorkspaceCancelsContext) {
                currentCoroutineContext().cancel(CancellationException("adapter cancelled child context"))
                yield()
            }
        }
        private fun fail(stage: RegistrationFailureStage) {
            if (failureStage == stage) error("registration secret failure")
        }
    }

    private class FakeActions : IdentityBoundaryActionPort {
        var cancelledScope: ActionIdentityScope? = null
        var cancelledStates: Set<ActionExecutionState>? = null
        var detachedScope: ActionIdentityScope? = null
        var cancelNeverReturns = false
        var detachNeverReturns = false
        var cancelThrowsCancellation = false
        var detachThrowsCancellation = false
        var cancelCancelsContext = false
        var detachCancelsContext = false
        var cancelCount = 0
        var detachCount = 0
        override suspend fun cancelPreExecution(identityScope: ActionIdentityScope, states: Set<ActionExecutionState>) {
            cancelCount += 1
            if (cancelNeverReturns) CompletableDeferred<Unit>().await()
            if (cancelThrowsCancellation) throw CancellationException("adapter self-cancelled")
            if (cancelCancelsContext) {
                currentCoroutineContext().cancel(CancellationException("adapter cancelled child context"))
                yield()
            }
            cancelledScope = identityScope
            cancelledStates = states
        }
        override suspend fun detachExecutingForReconciliation(identityScope: ActionIdentityScope) {
            detachCount += 1
            if (detachNeverReturns) CompletableDeferred<Unit>().await()
            if (detachThrowsCancellation) throw CancellationException("adapter self-cancelled")
            if (detachCancelsContext) {
                currentCoroutineContext().cancel(CancellationException("adapter cancelled child context"))
                yield()
            }
            detachedScope = identityScope
        }
        override suspend fun result(executionId: String, identityScope: ActionIdentityScope): ActionResult<JsonElement>? = null
    }

    private enum class RegistrationFailureStage { PREPARE, IDENTITY, CATALOG, CONTEXT, COMMIT }

    companion object {
        private fun candidate() = OaTenantCandidate("user-1", "tenant-1", 100, "Tenant", 0)
        private fun token(
            userId: String = "user-1",
            access: String = "access-secret",
            refresh: String = "refresh-secret",
        ) = OaTokenBundle(access, refresh, userId, 4_102_444_800_000)
        private fun permission(userId: String = "user-1") = OaPermissionInfo(
            permissions = setOf("case:read"),
            roles = setOf("lawyer"),
            user = OaPermissionUser(userId, "Lawyer"),
            menus = emptyList(),
        )
        private fun metadata() = BusinessAuthSessionMetadata("user-1", "tenant-1", "100")
    }
}
