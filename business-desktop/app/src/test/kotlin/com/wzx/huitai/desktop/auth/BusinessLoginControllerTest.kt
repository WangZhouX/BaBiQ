package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.security.LocalCredentialStoreUnavailableException
import com.wzx.huitai.integration.oa.auth.OaTenantCandidate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessLoginControllerTest {
    @Test
    fun `submit validates locally and valid input requests slider before any authentication call`() = runTest {
        val authentication = FakeAuthenticationOperations()
        val controller = BusinessLoginController(authentication, FakeRememberedLoginPort())

        controller.submit()
        assertEquals(BusinessLoginErrorCode.INVALID_ACCOUNT, controller.state.value.error?.code)
        controller.updateAccount("lawyer@example.com")
        controller.updatePassword("abcdefgh")
        controller.updateAgreement(true)
        controller.submit()
        assertEquals(BusinessLoginErrorCode.INVALID_PASSWORD_FORMAT, controller.state.value.error?.code)
        controller.updatePassword("password8")
        controller.submit()

        assertEquals(BusinessSliderState.REQUESTED, controller.state.value.slider)
        assertTrue(authentication.calls.isEmpty())
    }

    @Test
    fun `slider failure or close never requests tenant candidates`() = runTest {
        val authentication = FakeAuthenticationOperations()
        val controller = validController(authentication)

        controller.submit()
        controller.completeSlider(false)
        controller.submit()
        controller.dismissSlider()

        assertTrue(authentication.calls.isEmpty())
        assertEquals(BusinessSliderState.IDLE, controller.state.value.slider)
    }

    @Test
    fun `single available tenant authenticates automatically saves remembered login only after ready and clears password`() = runTest {
        val candidate = candidate("tenant-1")
        val authentication = FakeAuthenticationOperations(candidates = listOf(candidate))
        val remembered = FakeRememberedLoginPort()
        val controller = validController(authentication, remembered)

        controller.submit()
        controller.completeSlider(true)

        assertEquals(listOf("find", "authenticate:tenant-1"), authentication.calls)
        assertEquals(listOf("lawyer@example.com" to "password8"), remembered.saved)
        assertEquals("", controller.state.value.password)
        assertEquals(BusinessAccessGateState.READY, authentication.gate.value)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `multiple tenants require explicit selection disabled tenants cannot be selected and cancel is not a red failure`() = runTest {
        val enabled1 = candidate("tenant-1")
        val disabled = candidate("tenant-2", enterStatus = 1)
        val enabled2 = candidate("tenant-3")
        val authentication = FakeAuthenticationOperations(candidates = listOf(enabled1, disabled, enabled2))
        val controller = validController(authentication)

        controller.submit()
        controller.completeSlider(true)

        assertEquals(BusinessAccessGateState.SELECTING_TENANT, authentication.gate.value)
        assertEquals(3, controller.state.value.tenantCandidates.size)
        assertFalse(controller.state.value.tenantCandidates[1].enabled)
        controller.selectTenant(disabled)
        assertEquals(listOf("find", "selecting"), authentication.calls)

        controller.cancelTenantSelection()
        assertTrue(controller.state.value.tenantCandidates.isEmpty())
        assertNull(controller.state.value.error)
        assertEquals(BusinessLoginErrorCode.TENANT_SELECTION_CANCELLED, controller.state.value.notice?.code)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, authentication.gate.value)
    }

    @Test
    fun `no candidate and no available tenant use distinct stable errors`() = runTest {
        val accountMissing = validController(FakeAuthenticationOperations(candidates = emptyList()))
        accountMissing.submit()
        accountMissing.completeSlider(true)
        assertEquals(BusinessLoginErrorCode.ACCOUNT_NOT_FOUND, accountMissing.state.value.error?.code)

        val unavailable = validController(
            FakeAuthenticationOperations(candidates = listOf(candidate("tenant-1", 1), candidate("tenant-2", 2))),
        )
        unavailable.submit()
        unavailable.completeSlider(true)
        assertEquals(BusinessLoginErrorCode.TENANT_UNAVAILABLE, unavailable.state.value.error?.code)
    }

    @Test
    fun `duplicate submit while tenant lookup is running does not create a concurrent request`() = runTest {
        val authentication = FakeAuthenticationOperations(candidates = listOf(candidate("tenant-1"))).apply {
            findStarted = CompletableDeferred()
            findRelease = CompletableDeferred()
        }
        val controller = validController(authentication)
        controller.submit()
        val first = async { controller.completeSlider(true) }
        authentication.findStarted!!.await()
        runCurrent()

        controller.completeSlider(true)
        assertEquals(1, authentication.maxConcurrent.get())
        authentication.findRelease!!.complete(Unit)
        first.await()
        assertEquals(1, authentication.calls.count { it == "find" })
    }

    @Test
    fun `initialize restores remembered form invalid entry becomes warning and remember off clears entry`() = runTest {
        val remembered = FakeRememberedLoginPort(loaded = RememberedLoginValue("saved@example.com", "saved123".toCharArray()))
        val controller = BusinessLoginController(FakeAuthenticationOperations(), remembered)
        controller.initialize()
        assertEquals("saved@example.com", controller.state.value.account)
        assertEquals("saved123", controller.state.value.password)
        assertTrue(controller.state.value.remember)

        controller.updateRemember(false)
        assertEquals(1, remembered.clearCount)

        val invalid = FakeRememberedLoginPort(loadFailure = BusinessLoginException(BusinessLoginErrorCode.REMEMBERED_LOGIN_INVALID))
        val invalidController = BusinessLoginController(FakeAuthenticationOperations(), invalid)
        invalidController.initialize()
        assertEquals(BusinessLoginErrorCode.REMEMBERED_LOGIN_INVALID, invalidController.state.value.notice?.code)
        assertNull(invalidController.state.value.error)
    }

    @Test
    fun `stable failures are redacted cancellation propagates and close clears password`() = runTest {
        val secret = "remote-password-token-body"
        val authentication = FakeAuthenticationOperations(candidates = listOf(candidate("tenant-1"))).apply {
            authenticateFailure = BusinessAuthenticationException(BusinessLoginErrorCode.INVALID_CREDENTIALS)
        }
        val controller = validController(authentication)
        controller.submit()
        controller.completeSlider(true)
        assertEquals(BusinessLoginErrorCode.INVALID_CREDENTIALS, controller.state.value.error?.code)
        assertEquals("", controller.state.value.password)
        assertFalse(controller.state.value.toString().contains(secret))

        authentication.authenticateFailure = CancellationException(secret)
        controller.updatePassword("password8")
        controller.submit()
        assertFailsWith<CancellationException> { controller.completeSlider(true) }
        controller.close()
        controller.close()
        assertEquals("", controller.state.value.password)
        assertFalse(controller.toString().contains(secret))
    }

    @Test
    fun `close cancels active request and late cancellation ignoring result cannot save or mutate state`() = runTest {
        val authentication = FakeAuthenticationOperations(candidates = listOf(candidate("tenant-1"))).apply {
            authenticateStarted = CompletableDeferred()
            authenticateRelease = CompletableDeferred()
            ignoreAuthenticateCancellation = true
        }
        val remembered = FakeRememberedLoginPort()
        val controller = validController(authentication, remembered)
        controller.submit()
        val request = async { controller.completeSlider(true) }
        authentication.authenticateStarted!!.await()

        controller.close()
        controller.updateAccount("late@example.com")
        controller.updatePassword("latepass8")
        controller.updateAgreement(false)
        controller.updateRemember(false)
        controller.dismissSlider()
        controller.cancelTenantSelection()
        authentication.authenticateRelease!!.complete(Unit)
        assertFailsWith<CancellationException> { request.await() }

        assertEquals("lawyer@example.com", controller.state.value.account)
        assertEquals("", controller.state.value.password)
        assertFalse(controller.state.value.submitting)
        assertTrue(controller.state.value.agreementAccepted)
        assertTrue(controller.state.value.remember)
        assertTrue(remembered.saved.isEmpty())
        assertEquals(0, remembered.clearCount)
    }

    @Test
    fun `remembered login value close is idempotent and rejects later password copies`() {
        val source = "password8".toCharArray()
        val remembered = RememberedLoginValue("lawyer@example.com", source)
        source.fill('x')
        remembered.close()
        remembered.close()

        val failure = assertFailsWith<IllegalStateException> { remembered.copyPassword() }
        assertFalse(failure.toString().contains("password8"))
    }

    @Test
    fun `remembered save unavailable after ready revokes authentication before reporting error`() = runTest {
        val authentication = FakeAuthenticationOperations(candidates = listOf(candidate("tenant-1")))
        val remembered = FakeRememberedLoginPort(
            saveFailure = LocalCredentialStoreUnavailableException(),
        )
        val controller = validController(authentication, remembered)

        controller.submit()
        controller.completeSlider(true)

        assertEquals(1, authentication.localCredentialStoreUnavailableCount)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, authentication.gate.value)
        assertEquals(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE, controller.state.value.error?.code)
        assertTrue(remembered.saved.isEmpty())
    }

    @Test
    fun `remembered clear unavailable after ready revokes authentication before reporting error`() = runTest {
        val authentication = FakeAuthenticationOperations(candidates = listOf(candidate("tenant-1")))
        val remembered = FakeRememberedLoginPort(clearFailureAt = 2)
        val controller = validController(authentication, remembered)
        controller.updateRemember(false)

        controller.submit()
        controller.completeSlider(true)

        assertEquals(1, authentication.localCredentialStoreUnavailableCount)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, authentication.gate.value)
        assertEquals(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE, controller.state.value.error?.code)
        assertEquals(2, remembered.clearCount)
    }

    private fun validController(
        authentication: FakeAuthenticationOperations,
        remembered: FakeRememberedLoginPort = FakeRememberedLoginPort(),
    ) = BusinessLoginController(authentication, remembered).also {
        it.updateAccount("lawyer@example.com")
        it.updatePassword("password8")
        it.updateAgreement(true)
    }

    private fun candidate(tenantId: String, enterStatus: Int = 0) = OaTenantCandidate(
        userId = "user-1",
        tenantId = tenantId,
        platformId = 100,
        tenantName = tenantId,
        tenantEnterStatus = enterStatus,
    )

    private class FakeAuthenticationOperations(
        var candidates: List<OaTenantCandidate> = emptyList(),
    ) : BusinessAuthenticationOperations {
        override val gate = kotlinx.coroutines.flow.MutableStateFlow(BusinessAccessGateState.SIGNED_OUT)
        val calls = mutableListOf<String>()
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        var findStarted: CompletableDeferred<Unit>? = null
        var findRelease: CompletableDeferred<Unit>? = null
        var authenticateFailure: Throwable? = null
        var authenticateStarted: CompletableDeferred<Unit>? = null
        var authenticateRelease: CompletableDeferred<Unit>? = null
        var ignoreAuthenticateCancellation = false
        var localCredentialStoreUnavailableCount = 0

        override suspend fun findTenantCandidates(account: String): List<OaTenantCandidate> {
            calls += "find"
            val count = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, count) }
            return try {
                gate.value = BusinessAccessGateState.VERIFYING
                findStarted?.complete(Unit)
                findRelease?.await()
                candidates
            } finally {
                concurrent.decrementAndGet()
            }
        }

        override fun enterTenantSelection() {
            calls += "selecting"
            gate.value = BusinessAccessGateState.SELECTING_TENANT
        }

        override fun cancelTenantSelection() {
            calls += "cancel-selection"
            gate.value = BusinessAccessGateState.SIGNED_OUT
        }

        override suspend fun authenticate(account: String, password: CharArray, candidate: OaTenantCandidate) {
            try {
                calls += "authenticate:${candidate.tenantId}"
                authenticateStarted?.complete(Unit)
                try {
                    authenticateRelease?.await()
                } catch (cancelled: CancellationException) {
                    if (!ignoreAuthenticateCancellation) throw cancelled
                    withContext(NonCancellable) { authenticateRelease?.await() }
                }
                authenticateFailure?.let { throw it }
                gate.value = BusinessAccessGateState.READY
            } finally {
                password.fill('\u0000')
            }
        }

        override suspend fun onLocalCredentialStoreUnavailable() {
            localCredentialStoreUnavailableCount += 1
            gate.value = BusinessAccessGateState.SIGNED_OUT
        }
    }

    private class FakeRememberedLoginPort(
        var loaded: RememberedLoginValue? = null,
        var loadFailure: Throwable? = null,
        var saveFailure: Throwable? = null,
        var clearFailureAt: Int? = null,
    ) : BusinessRememberedLoginPort {
        val saved = mutableListOf<Pair<String, String>>()
        var clearCount = 0

        override fun load(): RememberedLoginValue? {
            loadFailure?.let { throw it }
            return loaded
        }

        override fun saveOrReplace(account: String, password: CharArray) {
            saveFailure?.let { throw it }
            saved += account to password.concatToString()
        }

        override fun clear() {
            clearCount += 1
            if (clearFailureAt == clearCount) throw LocalCredentialStoreUnavailableException()
        }
    }
}
