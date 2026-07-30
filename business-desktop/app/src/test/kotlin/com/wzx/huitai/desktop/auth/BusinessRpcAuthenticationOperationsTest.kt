package com.wzx.huitai.desktop.auth

import com.wzx.huitai.agent.business.BusinessRpcException
import com.wzx.huitai.agent.business.auth.BusinessAuthClient
import com.wzx.huitai.agent.business.auth.BusinessAuthStateChangeCode
import com.wzx.huitai.agent.business.auth.BusinessAuthStateChanged
import com.wzx.huitai.agent.business.auth.BusinessAuthStatus
import com.wzx.huitai.agent.business.auth.BusinessSessionView
import com.wzx.huitai.agent.business.auth.BusinessTenantCandidate as RpcBusinessTenantCandidate
import com.wzx.huitai.desktop.state.BusinessIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.yield

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessRpcAuthenticationOperationsTest {
    @Test
    fun `numeric zero tenant status remains selectable and login sends only opaque candidate id`() = runTest {
        val client = FakeBusinessAuthClient(
            candidates = listOf(
                RpcBusinessTenantCandidate(
                    candidateId = "ticket-1",
                    name = "寰嬫墍涓€",
                    status = "0",
                    platformId = 101,
                    tenantEnterStatus = 0,
                ),
            ),
            loginResult = readySession(identityEpoch = 7),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)

        val candidates = operations.findTenantCandidates("lawyer@example.com")

        assertEquals(1, candidates.size)
        assertEquals("ticket-1", candidates.single().candidateId)
        assertEquals("寰嬫墍涓€", candidates.single().name)
        assertEquals(101, candidates.single().platformId)
        assertEquals(0, candidates.single().tenantEnterStatus)

        operations.authenticate("lawyer@example.com", "password8".toCharArray(), candidates.single())

        assertEquals("ticket-1", client.loginCandidateId)
        assertEquals(BusinessAccessGateState.READY, operations.gate.value)
        assertEquals("user-1", registry.currentIdentity()?.userId)
        assertEquals("tenant-1", registry.currentIdentity()?.tenantId)
        assertEquals("101", registry.currentIdentity()?.platformId)
    }

    @Test
    fun `disabled candidate status is preserved and an unissued candidate cannot be authenticated`() = runTest {
        val client = FakeBusinessAuthClient(
            candidates = listOf(
                RpcBusinessTenantCandidate("ticket-disabled", "disabled", "2", 100, 2),
            ),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)

        val disabled = operations.findTenantCandidates("lawyer@example.com").single()
        assertEquals(2, disabled.tenantEnterStatus)

        val forged = BusinessTenantCandidate("forged", "forged", 100, 0)
        val failure = assertFailsWith<BusinessAuthenticationException> {
            operations.authenticate("lawyer@example.com", "password8".toCharArray(), forged)
        }
        assertEquals(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR, failure.code)
        assertNull(client.loginCandidateId)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, operations.gate.value)
    }

    @Test
    fun `cancellation is not converted into a remote unavailable login error`() = runTest {
        val client = FakeBusinessAuthClient(
            candidates = listOf(RpcBusinessTenantCandidate("ticket-1", "寰嬫墍涓€", "0", 100, 0)),
            loginFailure = CancellationException("cancelled"),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)
        val candidate = operations.findTenantCandidates("lawyer@example.com").single()

        assertFailsWith<CancellationException> {
            operations.authenticate("lawyer@example.com", "password8".toCharArray(), candidate)
        }
        assertEquals(BusinessAccessGateState.SIGNED_OUT, operations.gate.value)
    }

    @Test
    fun `cancellation still completes signed-out projection cleanup`() = runTest {
        val events = mutableListOf<String>()
        val client = FakeBusinessAuthClient(
            loginFailure = CancellationException("cancelled"),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(
            client,
            registry,
            onSignedOut = {
                yield()
                events += "signed-out"
            },
        )
        val candidate = operations.findTenantCandidates("lawyer@example.com").single()

        assertFailsWith<CancellationException> {
            operations.authenticate("lawyer@example.com", "password8".toCharArray(), candidate)
        }

        assertEquals(listOf("signed-out"), events)
    }

    @Test
    fun `stable rpc errors map to stable login errors without exposing remote text`() = runTest {
        val client = FakeBusinessAuthClient(
            loginFailure = BusinessRpcException(
                remoteCode = -32043,
                businessCode = "BUSINESS_INVALID_PASSWORD",
            ),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)
        val candidate = operations.findTenantCandidates("lawyer@example.com").single()

        val failure = assertFailsWith<BusinessAuthenticationException> {
            operations.authenticate("lawyer@example.com", "password8".toCharArray(), candidate)
        }

        assertEquals(BusinessLoginErrorCode.INVALID_PASSWORD_FORMAT, failure.code)
        assertEquals("BusinessAuthenticationException(code=INVALID_PASSWORD_FORMAT)", failure.toString())
    }

    @Test
    fun `rpc projection decode failures map to protocol error`() = runTest {
        val client = FakeBusinessAuthClient(
            loginFailure = SerializationException("malformed session payload"),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)
        val candidate = operations.findTenantCandidates("lawyer@example.com").single()

        val failure = assertFailsWith<BusinessAuthenticationException> {
            operations.authenticate("lawyer@example.com", "password8".toCharArray(), candidate)
        }

        assertEquals(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR, failure.code)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, operations.gate.value)
    }

    @Test
    fun `ready and logout notify the local workspace projection after registry transition`() = runTest {
        val client = FakeBusinessAuthClient(loginResult = readySession(identityEpoch = 7))
        val registry = BusinessIdentityRegistry()
        val events = mutableListOf<String>()
        val operations = operations(
            client,
            registry,
            onReady = { identity ->
                events += "ready:${identity.identityEpoch}:${registry.currentIdentity()?.identityEpoch}"
            },
            onSignedOut = { events += "signed-out:${registry.currentIdentity()}" },
        )
        val candidate = operations.findTenantCandidates("lawyer@example.com").single()

        operations.authenticate("lawyer@example.com", "password8".toCharArray(), candidate)
        operations.logout()

        assertEquals(listOf("ready:7:7", "signed-out:null"), events)
    }

    @Test
    fun `logout revokes local projection before remote failure is returned`() = runTest {
        val events = mutableListOf<String>()
        val registry = BusinessIdentityRegistry()
        val client = FakeBusinessAuthClient(
            loginResult = readySession(identityEpoch = 7),
            logoutFailure = IllegalStateException("remote logout failed"),
            onLogout = { events += "remote-logout:${registry.currentIdentity()}" },
        )
        val operations = operations(
            client,
            registry,
            onReady = { events += "ready" },
            onSignedOut = { events += "signed-out:${registry.currentIdentity()}" },
        )
        val candidate = operations.findTenantCandidates("lawyer@example.com").single()
        operations.authenticate("lawyer@example.com", "password8".toCharArray(), candidate)

        val failure = assertFailsWith<IllegalStateException> { operations.logout() }

        assertEquals("remote logout failed", failure.message)
        assertEquals(listOf("ready", "signed-out:null", "remote-logout:null"), events)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, operations.gate.value)
    }

    @Test
    fun `tenant cancel cannot erase the next ready projection through queued cleanup`() = runTest {
        val client = FakeBusinessAuthClient(
            candidates = listOf(RpcBusinessTenantCandidate("ticket-1", "cancel", "0", 100, 0)),
            loginResult = readySession(identityEpoch = 19),
        )
        val registry = BusinessIdentityRegistry()
        var projectedIdentity: com.wzx.huitai.desktop.state.BusinessIdentity? = null
        var signedOutCount = 0
        val operations = operations(
            client,
            registry,
            onReady = { identity -> projectedIdentity = identity },
            onSignedOut = { signedOutCount += 1 },
        )

        operations.findTenantCandidates("lawyer@example.com")
        operations.enterTenantSelection()
        operations.cancelTenantSelection()
        val candidate = operations.findTenantCandidates("lawyer@example.com").single()
        operations.authenticate("lawyer@example.com", "password8".toCharArray(), candidate)

        assertEquals(19, projectedIdentity?.identityEpoch)
        runCurrent()

        assertEquals(BusinessAccessGateState.READY, operations.gate.value)
        assertEquals(19, projectedIdentity?.identityEpoch)
        assertEquals(0, signedOutCount)
        assertTrue("logout" !in client.calls)
    }

    @Test
    fun `tenant cancel invalidates issued candidates without remote auth side effects`() = runTest {
        val client = FakeBusinessAuthClient(loginResult = readySession(identityEpoch = 20))
        val registry = BusinessIdentityRegistry()
        var signedOutCount = 0
        val operations = operations(client, registry, onSignedOut = { signedOutCount += 1 })
        val candidate = operations.findTenantCandidates("lawyer@example.com").single()
        operations.enterTenantSelection()

        operations.cancelTenantSelection()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, operations.gate.value)
        assertNull(registry.currentIdentity())
        assertEquals(0, signedOutCount)
        assertTrue(client.calls.isEmpty())
        val failure = assertFailsWith<BusinessAuthenticationException> {
            operations.authenticate("lawyer@example.com", "password8".toCharArray(), candidate)
        }
        assertEquals(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR, failure.code)
        assertNull(client.loginCandidateId)
    }

    @Test
    fun `ready identity ignores a stale tenant cancel`() = runTest {
        val client = FakeBusinessAuthClient(sessionResult = readySession(identityEpoch = 20))
        val registry = BusinessIdentityRegistry()
        var signedOutCount = 0
        val operations = operations(
            client,
            registry,
            onSignedOut = { signedOutCount += 1 },
        )
        operations.restore()
        val readyIdentity = registry.currentIdentity()

        operations.cancelTenantSelection()

        assertEquals(BusinessAccessGateState.READY, operations.gate.value)
        assertEquals(readyIdentity, registry.currentIdentity())
        assertEquals(0, signedOutCount)
        assertEquals(listOf("session"), client.calls)
    }

    @Test
    fun `tenant cancel invalidates an authenticating attempt without logout`() = runTest {
        val client = DelayedLoginClient()
        val registry = BusinessIdentityRegistry()
        val readyEvents = mutableListOf<Long>()
        var signedOutCount = 0
        val operations = operations(
            client,
            registry,
            onReady = { identity -> readyEvents += identity.identityEpoch },
            onSignedOut = { signedOutCount += 1 },
        )
        val candidate = operations.findTenantCandidates("lawyer@example.com").single()
        operations.enterTenantSelection()
        val authentication = async {
            runCatching {
                operations.authenticate("lawyer@example.com", "password8".toCharArray(), candidate)
            }
        }
        client.loginStarted.await()

        operations.cancelTenantSelection()
        client.releaseLogin.complete(Unit)
        val result = authentication.await()

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, operations.gate.value)
        assertNull(registry.currentIdentity())
        assertTrue(readyEvents.isEmpty())
        assertEquals(0, signedOutCount)
        assertEquals(0, client.logoutCount)
    }

    @Test
    fun `cancelled verifying lookup cannot publish late candidates`() = runTest {
        val client = DelayedTenantCandidatesClient()
        val registry = BusinessIdentityRegistry()
        var signedOutCount = 0
        val operations = operations(client, registry, onSignedOut = { signedOutCount += 1 })
        val lookup = async {
            runCatching { operations.findTenantCandidates("lawyer@example.com") }
        }
        client.firstLookupStarted.await()
        assertEquals(BusinessAccessGateState.VERIFYING, operations.gate.value)

        operations.cancelTenantSelection()
        client.releaseFirstLookup.complete(Unit)
        val result = lookup.await()

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, operations.gate.value)
        assertNull(registry.currentIdentity())
        assertEquals(0, signedOutCount)
        val failure = assertFailsWith<BusinessAuthenticationException> {
            operations.authenticate(
                "lawyer@example.com",
                "password8".toCharArray(),
                client.staleCandidate,
            )
        }
        assertEquals(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR, failure.code)
        assertEquals(0, client.loginCount)
        assertEquals(0, client.logoutCount)
    }

    @Test
    fun `late verifying cancellation or failure cannot clear a newer ready identity`() = runTest {
        val lateFailures = listOf(
            CancellationException("stale lookup cancelled"),
            IllegalStateException("stale lookup failed"),
        )
        lateFailures.forEachIndexed { index, lateFailure ->
            val identityEpoch = 30L + index
            val client = DelayedTenantCandidatesClient(
                lateFailure = lateFailure,
                readyIdentityEpoch = identityEpoch,
            )
            val registry = BusinessIdentityRegistry()
            val readyEvents = mutableListOf<Long>()
            var signedOutCount = 0
            val operations = operations(
                client,
                registry,
                onReady = { identity -> readyEvents += identity.identityEpoch },
                onSignedOut = { signedOutCount += 1 },
            )
            val staleLookup = async {
                runCatching { operations.findTenantCandidates("old@example.com") }
            }
            client.firstLookupStarted.await()
            operations.cancelTenantSelection()

            val candidate = operations.findTenantCandidates("new@example.com").single()
            operations.authenticate("new@example.com", "password8".toCharArray(), candidate)
            val readyIdentity = registry.currentIdentity()
            assertEquals(identityEpoch, readyIdentity?.identityEpoch)

            client.releaseFirstLookup.complete(Unit)
            assertTrue(staleLookup.await().isFailure)

            assertEquals(BusinessAccessGateState.READY, operations.gate.value)
            assertEquals(readyIdentity, registry.currentIdentity())
            assertEquals(listOf(identityEpoch), readyEvents)
            assertNull(operations.lastError.value)
            assertEquals(0, signedOutCount)
            assertEquals(1, client.loginCount)
            assertEquals(0, client.logoutCount)
        }
    }

    @Test
    fun `malformed ready projection is reported as protocol error and clears the gate`() = runTest {
        val client = FakeBusinessAuthClient(
            loginResult = BusinessSessionView(
                status = BusinessAuthStatus.READY,
                authSessionId = "auth-session-1",
                identityEpoch = 1,
                generation = 1,
            ),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)
        val candidate = operations.findTenantCandidates("lawyer@example.com").single()

        val failure = assertFailsWith<BusinessAuthenticationException> {
            operations.authenticate("lawyer@example.com", "password8".toCharArray(), candidate)
        }

        assertEquals(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR, failure.code)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, operations.gate.value)
    }

    @Test
    fun `ready projection without server platform id fails closed`() = runTest {
        val client = FakeBusinessAuthClient(
            loginResult = readySession(identityEpoch = 7).copy(platformId = null),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)
        val candidate = operations.findTenantCandidates("lawyer@example.com").single()

        val failure = assertFailsWith<BusinessAuthenticationException> {
            operations.authenticate("lawyer@example.com", "password8".toCharArray(), candidate)
        }

        assertEquals(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR, failure.code)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, operations.gate.value)
        assertNull(registry.currentIdentity())
    }

    @Test
    fun `restore projects server READY session into the local gate before notifying workspace`() = runTest {
        val registry = BusinessIdentityRegistry()
        val events = mutableListOf<String>()
        val operations = operations(
            FakeBusinessAuthClient(loginResult = readySession(identityEpoch = 12)),
            registry,
            onReady = { identity -> events += "ready:${identity.identityEpoch}" },
        )

        operations.restore()

        assertEquals(BusinessAccessGateState.READY, operations.gate.value)
        assertEquals(12, registry.currentIdentity()?.identityEpoch)
        assertEquals(listOf("ready:12"), events)
        assertTrue(registry.currentIdentity()?.authSessionId?.isNotBlank() == true)
    }

    @Test
    fun `restore projects an already READY session without calling restore endpoint`() = runTest {
        val client = FakeBusinessAuthClient(
            sessionResult = readySession(identityEpoch = 14),
            restoreResult = IllegalStateException("restore endpoint must not be called"),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)

        operations.restore()

        assertEquals(listOf("session"), client.calls)
        assertEquals(BusinessAccessGateState.READY, operations.gate.value)
        assertEquals(14, registry.currentIdentity()?.identityEpoch)
    }

    @Test
    fun `restore refreshes a DETACHED session through the server restore endpoint`() = runTest {
        val client = FakeBusinessAuthClient(
            sessionResult = BusinessSessionView(
                status = BusinessAuthStatus.DETACHED,
                identityEpoch = 0,
                generation = 8,
            ),
            restoreResult = readySession(identityEpoch = 15),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)

        operations.restore()

        assertEquals(listOf("session", "restore"), client.calls)
        assertEquals(BusinessAccessGateState.READY, operations.gate.value)
        assertEquals(15, registry.currentIdentity()?.identityEpoch)
    }

    @Test
    fun `restore keeps a SIGNED_OUT session signed out without remote restore`() = runTest {
        val client = FakeBusinessAuthClient(
            sessionResult = BusinessSessionView(
                status = BusinessAuthStatus.SIGNED_OUT,
                identityEpoch = 0,
                generation = 0,
            ),
            restoreResult = IllegalStateException("restore endpoint must not be called"),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)

        operations.restore()

        assertEquals(listOf("session"), client.calls)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, operations.gate.value)
        assertNull(registry.currentIdentity())
    }

    @Test
    fun `reconnect probes detached session then attaches with its opaque handle without restore fallback`() = runTest {
        val client = FakeBusinessAuthClient(
            sessionResult = BusinessSessionView(
                status = BusinessAuthStatus.DETACHED,
                identityEpoch = 0,
                generation = 8,
                attachHandle = "attach-handle-8",
            ),
            attachResult = readySession(identityEpoch = 16),
            restoreResult = IllegalStateException("restore endpoint must not be called during reconnect"),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)

        operations.attachAfterReconnect()

        assertEquals(listOf("session", "attach"), client.calls)
        assertEquals(listOf("attach-handle-8"), client.attachHandles)
        assertEquals(BusinessAccessGateState.READY, operations.gate.value)
        assertEquals(16, registry.currentIdentity()?.identityEpoch)
    }

    @Test
    fun `reconnect detached session without attach handle fails closed without calling restore`() = runTest {
        val client = FakeBusinessAuthClient(
            sessionResult = BusinessSessionView(
                status = BusinessAuthStatus.DETACHED,
                identityEpoch = 0,
                generation = 8,
            ),
            restoreResult = IllegalStateException("restore endpoint must not be called during reconnect"),
        )
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)

        val failure = assertFailsWith<BusinessAuthenticationException> {
            operations.attachAfterReconnect()
        }

        assertEquals(BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR, failure.code)
        assertEquals(listOf("session"), client.calls)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, operations.gate.value)
        assertNull(registry.currentIdentity())
    }

    @Test
    fun `connection unavailable immediately closes a ready local gate without remote logout`() = runTest {
        val client = FakeBusinessAuthClient(sessionResult = readySession(identityEpoch = 17))
        val registry = BusinessIdentityRegistry()
        var signedOutCount = 0
        var recoveringCount = 0
        val operations = operations(
            client,
            registry,
            onSignedOut = { signedOutCount += 1 },
            onRecovering = { recoveringCount += 1 },
        )
        operations.restore()
        assertEquals(BusinessAccessGateState.READY, operations.gate.value)

        operations.onConnectionUnavailable()

        assertEquals(BusinessAccessGateState.RESTORING, operations.gate.value)
        assertNull(registry.currentIdentity())
        assertEquals(listOf("session"), client.calls)
        assertEquals(0, signedOutCount)
        assertEquals(1, recoveringCount)
    }

    @Test
    fun `lifecycle close clears local authentication without remote logout`() = runTest {
        val client = FakeBusinessAuthClient(sessionResult = readySession(identityEpoch = 18))
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)
        operations.restore()
        assertEquals(BusinessAccessGateState.READY, operations.gate.value)

        operations.close()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, operations.gate.value)
        assertNull(registry.currentIdentity())
        assertEquals(listOf("session"), client.calls)
    }

    @Test
    fun `late reconnect result cannot overwrite the ready identity from a newer connection`() = runTest {
        val client = SupersedingAttachClient()
        val registry = BusinessIdentityRegistry()
        val events = mutableListOf<Long>()
        val operations = BusinessRpcAuthenticationOperations(
            client = client,
            identityRegistry = registry,
            desktopInstanceId = "desktop-1",
            desktopSessionId = "session-1",
            platformId = 100,
            onReady = { identity -> events += identity.identityEpoch },
        )

        val stale = async {
            runCatching { operations.attachAfterReconnect() }
        }
        client.staleAttachStarted.await()
        operations.onConnectionUnavailable()

        operations.attachAfterReconnect()
        assertEquals(22, registry.currentIdentity()?.identityEpoch)

        client.releaseStaleAttach.complete(Unit)
        assertTrue(stale.await().isFailure)

        assertEquals(BusinessAccessGateState.READY, operations.gate.value)
        assertEquals(22, registry.currentIdentity()?.identityEpoch)
        assertEquals(listOf(22L), events)
    }

    @Test
    fun `confirmed authentication expiry closes the captured local snapshot without remote logout`() = runTest {
        val client = FakeBusinessAuthClient(
            sessionResult = signedOutSession(generation = 4),
        )
        val registry = readyRegistry()
        var signedOutCount = 0
        var authenticationExpiredCount = 0
        val operations = operations(
            client,
            registry,
            onSignedOut = { signedOutCount += 1 },
            onAuthenticationExpiredState = { authenticationExpiredCount += 1 },
        )

        operations.reconcileAuthStateChanged(
            authStateChanged(
                generation = 4,
                businessCode = BusinessAuthStateChangeCode.AUTH_EXPIRED,
            ),
        )

        assertEquals(BusinessAccessGateState.SIGNED_OUT, registry.gate.value)
        assertNull(registry.currentIdentity())
        assertEquals(BusinessLoginErrorCode.AUTH_EXPIRED, operations.lastError.value?.code)
        assertEquals(0, signedOutCount)
        assertEquals(1, authenticationExpiredCount)
        assertEquals(1, client.calls.count { it == "session" })
        assertEquals(0, client.calls.count { it == "logout" })
    }

    @Test
    fun `confirmed membership expiry closes the captured local snapshot without remote logout`() = runTest {
        val client = FakeBusinessAuthClient(
            sessionResult = signedOutSession(generation = 7),
        )
        val registry = readyRegistry()
        var signedOutCount = 0
        var membershipExpiredCount = 0
        val operations = operations(
            client,
            registry,
            onSignedOut = { signedOutCount += 1 },
            onMembershipExpiredState = { membershipExpiredCount += 1 },
        )

        operations.reconcileAuthStateChanged(
            authStateChanged(
                generation = 7,
                businessCode = BusinessAuthStateChangeCode.MEMBERSHIP_EXPIRED,
            ),
        )

        assertEquals(BusinessAccessGateState.SIGNED_OUT, registry.gate.value)
        assertNull(registry.currentIdentity())
        assertEquals(BusinessLoginErrorCode.MEMBERSHIP_EXPIRED, operations.lastError.value?.code)
        assertEquals(0, signedOutCount)
        assertEquals(1, membershipExpiredCount)
        assertEquals(1, client.calls.count { it == "session" })
        assertEquals(0, client.calls.count { it == "logout" })
    }

    @Test
    fun `terminal notification requires matching remote session id state and generation`() = runTest {
        val unconfirmedSessions = listOf(
            signedOutSession(authSessionId = "auth-session-other", generation = 4),
            BusinessSessionView(
                status = BusinessAuthStatus.DETACHED,
                authSessionId = "auth-session-1",
                identityEpoch = 0,
                generation = 4,
            ),
            signedOutSession(generation = 5),
        )

        unconfirmedSessions.forEach { session ->
            val client = FakeBusinessAuthClient(sessionResult = session)
            val registry = readyRegistry()
            val before = registry.snapshot.value
            var signedOutCount = 0
            val operations = operations(client, registry, onSignedOut = { signedOutCount += 1 })

            operations.reconcileAuthStateChanged(authStateChanged(generation = 4))

            assertEquals(before, registry.snapshot.value)
            assertNull(operations.lastError.value)
            assertEquals(0, signedOutCount)
            assertEquals(1, client.calls.count { it == "session" })
            assertEquals(0, client.calls.count { it == "logout" })
        }
    }

    @Test
    fun `matching backend generation is independent from a higher local registry generation`() = runTest {
        val client = FakeBusinessAuthClient(
            sessionResult = signedOutSession(generation = 3),
        )
        val registry = BusinessIdentityRegistry()
        repeat(9) { registry.invalidate(BusinessAccessGateState.SIGNED_OUT) }
        assertTrue(registry.publishReady(identity(), registry.currentGeneration()))
        val localGeneration = registry.currentGeneration()
        var signedOutCount = 0
        val operations = operations(client, registry, onSignedOut = { signedOutCount += 1 })

        operations.reconcileAuthStateChanged(authStateChanged(generation = 3))

        assertEquals(BusinessAccessGateState.SIGNED_OUT, registry.gate.value)
        assertEquals(localGeneration + 1, registry.currentGeneration())
        assertEquals(BusinessLoginErrorCode.AUTH_EXPIRED, operations.lastError.value?.code)
        assertEquals(1, signedOutCount)
        assertEquals(1, client.calls.count { it == "session" })
        assertEquals(0, client.calls.count { it == "logout" })
    }

    @Test
    fun `higher generation ready probe replaces stale terminal notification with server ready projection`() = runTest {
        val client = FakeBusinessAuthClient(
            sessionResult = readySession(
                identityEpoch = 2,
                generation = 5,
                authSessionId = "auth-session-2",
            ),
        )
        val registry = readyRegistry()
        val readyEvents = mutableListOf<BusinessIdentity>()
        val operations = operations(client, registry, onReady = { readyEvents += it })

        operations.reconcileAuthStateChanged(authStateChanged(generation = 4))

        assertEquals(BusinessAccessGateState.READY, registry.gate.value)
        assertEquals("auth-session-2", registry.currentIdentity()?.authSessionId)
        assertEquals(2, registry.currentIdentity()?.identityEpoch)
        assertEquals(listOf("auth-session-2"), readyEvents.map(BusinessIdentity::authSessionId))
        assertNull(operations.lastError.value)
        assertEquals(1, client.calls.count { it == "session" })
        assertEquals(0, client.calls.count { it == "logout" })
    }

    @Test
    fun `delayed old signed out probe cannot overwrite same generation replacement identity`() = runTest {
        val client = DelayedReconciliationClient(
            result = signedOutSession(generation = 4),
        )
        val registry = readyRegistry()
        val capturedGeneration = registry.currentGeneration()
        var signedOutCount = 0
        val operations = operations(client, registry, onSignedOut = { signedOutCount += 1 })

        val reconciliation = async {
            operations.reconcileAuthStateChanged(authStateChanged(generation = 4))
        }
        client.sessionStarted.await()
        val replacement = identity(
            authSessionId = "auth-session-2",
            identityEpoch = 2,
            userId = "user-2",
        )
        assertTrue(registry.publishReady(replacement, expectedGeneration = capturedGeneration))
        client.releaseSession.complete(Unit)
        reconciliation.await()

        assertEquals(BusinessAccessGateState.READY, registry.gate.value)
        assertEquals(replacement, registry.currentIdentity())
        assertEquals(capturedGeneration, registry.currentGeneration())
        assertNull(operations.lastError.value)
        assertEquals(0, signedOutCount)
        assertEquals(1, client.calls.count { it == "session" })
        assertEquals(0, client.calls.count { it == "logout" })
    }

    @Test
    fun `terminal reconciliation makes an older same connection startup probe stale`() = runTest {
        val client = ReorderedStartupReconciliationClient()
        val registry = BusinessIdentityRegistry()
        val operations = BusinessRpcAuthenticationOperations(
            client = client,
            identityRegistry = registry,
            desktopInstanceId = "desktop-1",
            desktopSessionId = "session-1",
            platformId = 100,
            currentConnectionId = { "connection-1" },
        )

        val startupPreparation = async { operations.prepareStartup() }
        client.startupProbeStarted.await()

        operations.reconcileAuthStateChanged(authStateChanged(generation = 4))
        assertEquals(BusinessAccessGateState.SIGNED_OUT, registry.gate.value)

        client.releaseStartupProbe.complete(Unit)
        startupPreparation.await()
        operations.restore()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, registry.gate.value)
        assertNull(registry.currentIdentity())
        assertEquals(3, client.calls.count { it == "session" })
        assertEquals(0, client.calls.count { it == "logout" })
    }

    @Test
    fun `prepared startup session is consumed only while its full registry snapshot is current`() = runTest {
        val client = SequencedStartupSessionClient(
            readySession(identityEpoch = 1, generation = 3),
            signedOutSession(generation = 4),
        )
        val registry = BusinessIdentityRegistry()
        val operations = BusinessRpcAuthenticationOperations(
            client = client,
            identityRegistry = registry,
            desktopInstanceId = "desktop-1",
            desktopSessionId = "session-1",
            platformId = 100,
            currentConnectionId = { "connection-1" },
        )

        operations.prepareStartup()
        registry.transitionTo(BusinessAccessGateState.SIGNED_OUT)
        operations.restore()

        assertEquals(BusinessAccessGateState.SIGNED_OUT, registry.gate.value)
        assertNull(registry.currentIdentity())
        assertEquals(2, client.calls.count { it == "session" })
        assertEquals(0, client.calls.count { it == "logout" })
    }

    @Test
    fun `late startup restore cannot supersede an interactive tenant lookup`() = runTest {
        val client = DelayedTenantCandidatesClient()
        val registry = BusinessIdentityRegistry()
        val operations = operations(client, registry)

        val lookup = async { operations.findTenantCandidates("lawyer@example.com") }
        client.firstLookupStarted.await()

        operations.restore()
        client.releaseFirstLookup.complete(Unit)

        assertEquals("ticket-stale", lookup.await().single().candidateId)
        assertEquals(BusinessAccessGateState.VERIFYING, registry.gate.value)
        assertNull(registry.currentIdentity())
    }

    private fun operations(
        client: BusinessAuthClient,
        registry: BusinessIdentityRegistry,
        onReady: suspend (com.wzx.huitai.desktop.state.BusinessIdentity) -> Unit = {},
        onSignedOut: suspend () -> Unit = {},
        onRecovering: suspend () -> Unit = {},
        onAuthenticationExpiredState: suspend () -> Unit = onSignedOut,
        onMembershipExpiredState: suspend () -> Unit = onSignedOut,
    ) = BusinessRpcAuthenticationOperations(
        client = client,
        identityRegistry = registry,
        desktopInstanceId = "desktop-1",
        desktopSessionId = "session-1",
        platformId = 100,
        onReady = onReady,
        onSignedOut = onSignedOut,
        onRecovering = onRecovering,
        onAuthenticationExpiredState = onAuthenticationExpiredState,
        onMembershipExpiredState = onMembershipExpiredState,
    )

    private fun readyRegistry(
        initialIdentity: BusinessIdentity = identity(),
    ) = BusinessIdentityRegistry().apply {
        check(publishReady(initialIdentity, currentGeneration()))
    }

    private fun identity(
        authSessionId: String = "auth-session-1",
        identityEpoch: Long = 1,
        userId: String = "user-1",
    ) = BusinessIdentity(
        desktopInstanceId = "desktop-1",
        desktopSessionId = "session-1",
        authSessionId = authSessionId,
        identityEpoch = identityEpoch,
        userId = userId,
        tenantId = "tenant-1",
        platformId = "101",
        roles = setOf("LAWYER"),
        permissions = setOf("workbench:read"),
    )

    private fun authStateChanged(
        authSessionId: String = "auth-session-1",
        generation: Long,
        businessCode: BusinessAuthStateChangeCode = BusinessAuthStateChangeCode.AUTH_EXPIRED,
    ) = BusinessAuthStateChanged(
        authSessionId = authSessionId,
        state = BusinessAuthStatus.SIGNED_OUT,
        generation = generation,
        businessCode = businessCode,
    )

    private fun signedOutSession(
        authSessionId: String = "auth-session-1",
        generation: Long,
    ) = BusinessSessionView(
        status = BusinessAuthStatus.SIGNED_OUT,
        authSessionId = authSessionId,
        identityEpoch = 0,
        generation = generation,
    )

    private fun readySession(
        identityEpoch: Long,
        generation: Long = 3,
        authSessionId: String = "auth-session-1",
    ) = BusinessSessionView(
        status = BusinessAuthStatus.READY,
        authSessionId = authSessionId,
        identityEpoch = identityEpoch,
        generation = generation,
        platformId = "101",
        user = com.wzx.huitai.agent.business.auth.BusinessUserSummary("user-1", "寰嬪笀"),
        tenant = com.wzx.huitai.agent.business.auth.BusinessTenantSummary("tenant-1", "寰嬫墍"),
        roles = setOf("LAWYER"),
        permissions = setOf("workbench:read"),
    )

    private class FakeBusinessAuthClient(
        private val candidates: List<RpcBusinessTenantCandidate> = listOf(
            RpcBusinessTenantCandidate("ticket-1", "寰嬫墍涓€", "0", 100, 0),
        ),
        private val loginResult: BusinessSessionView = BusinessSessionView(
            status = BusinessAuthStatus.SIGNED_OUT,
            identityEpoch = 0,
            generation = 0,
        ),
        private val loginFailure: Throwable? = null,
        private val logoutFailure: Throwable? = null,
        private val onLogout: () -> Unit = {},
        private val sessionResult: BusinessSessionView? = null,
        private val attachResult: Any? = null,
        private val restoreResult: Any? = null,
    ) : BusinessAuthClient {
        var loginCandidateId: String? = null
        val calls = mutableListOf<String>()
        val attachHandles = mutableListOf<String>()

        override suspend fun session(): BusinessSessionView {
            calls += "session"
            return sessionResult ?: loginResult
        }
        override suspend fun attach(attachHandle: String): BusinessSessionView {
            calls += "attach"
            attachHandles += attachHandle
            val result = attachResult ?: loginResult
            if (result is Throwable) throw result
            return result as BusinessSessionView
        }
        override suspend fun tenantCandidates(account: String) = candidates
        override suspend fun login(account: String, password: CharArray, candidateId: String): BusinessSessionView {
            loginCandidateId = candidateId
            loginFailure?.let { throw it }
            return loginResult
        }
        override suspend fun restore(): BusinessSessionView {
            calls += "restore"
            val result = restoreResult ?: loginResult
            if (result is Throwable) throw result
            return result as BusinessSessionView
        }
        override suspend fun logout(): BusinessSessionView {
            calls += "logout"
            onLogout()
            logoutFailure?.let { throw it }
            return BusinessSessionView(BusinessAuthStatus.SIGNED_OUT, identityEpoch = 0, generation = 0)
        }
    }

    private inner class DelayedLoginClient : BusinessAuthClient {
        val loginStarted = CompletableDeferred<Unit>()
        val releaseLogin = CompletableDeferred<Unit>()
        var logoutCount = 0

        override suspend fun session(): BusinessSessionView =
            BusinessSessionView(BusinessAuthStatus.SIGNED_OUT, identityEpoch = 0, generation = 0)

        override suspend fun attach(attachHandle: String): BusinessSessionView = error("attach must not be called")

        override suspend fun tenantCandidates(account: String): List<RpcBusinessTenantCandidate> = listOf(
            RpcBusinessTenantCandidate("ticket-delayed", "delayed", "0", 100, 0),
        )

        override suspend fun login(
            account: String,
            password: CharArray,
            candidateId: String,
        ): BusinessSessionView = withContext(NonCancellable) {
            loginStarted.complete(Unit)
            releaseLogin.await()
            readySession(identityEpoch = 21)
        }

        override suspend fun restore(): BusinessSessionView = error("restore must not be called")

        override suspend fun logout(): BusinessSessionView {
            logoutCount += 1
            return BusinessSessionView(BusinessAuthStatus.SIGNED_OUT, identityEpoch = 0, generation = 0)
        }
    }

    private inner class DelayedTenantCandidatesClient(
        private val lateFailure: Throwable? = null,
        private val readyIdentityEpoch: Long = 29,
    ) : BusinessAuthClient {
        val firstLookupStarted = CompletableDeferred<Unit>()
        val releaseFirstLookup = CompletableDeferred<Unit>()
        val staleCandidate = BusinessTenantCandidate(
            candidateId = "ticket-stale",
            name = "stale tenant",
            platformId = 100,
            tenantEnterStatus = 0,
        )
        var loginCount = 0
        var logoutCount = 0
        private var lookupCount = 0

        override suspend fun session(): BusinessSessionView =
            BusinessSessionView(BusinessAuthStatus.SIGNED_OUT, identityEpoch = 0, generation = 0)

        override suspend fun attach(attachHandle: String): BusinessSessionView = error("attach must not be called")

        override suspend fun tenantCandidates(account: String): List<RpcBusinessTenantCandidate> {
            lookupCount += 1
            if (lookupCount == 1) {
                firstLookupStarted.complete(Unit)
                releaseFirstLookup.await()
                lateFailure?.let { throw it }
                return listOf(staleCandidate.toRpcCandidate())
            }
            return listOf(
                RpcBusinessTenantCandidate("ticket-fresh", "fresh tenant", "0", 100, 0),
            )
        }

        override suspend fun login(
            account: String,
            password: CharArray,
            candidateId: String,
        ): BusinessSessionView {
            loginCount += 1
            return readySession(identityEpoch = readyIdentityEpoch)
        }

        override suspend fun restore(): BusinessSessionView = error("restore must not be called")

        override suspend fun logout(): BusinessSessionView {
            logoutCount += 1
            return BusinessSessionView(BusinessAuthStatus.SIGNED_OUT, identityEpoch = 0, generation = 0)
        }

        private fun BusinessTenantCandidate.toRpcCandidate() = RpcBusinessTenantCandidate(
            candidateId = candidateId,
            name = name.orEmpty(),
            status = "0",
            platformId = platformId,
            tenantEnterStatus = tenantEnterStatus,
        )
    }

    private inner class SupersedingAttachClient : BusinessAuthClient {
        val staleAttachStarted = CompletableDeferred<Unit>()
        val releaseStaleAttach = CompletableDeferred<Unit>()
        private var sessionOrdinal = 0

        override suspend fun session(): BusinessSessionView {
            sessionOrdinal += 1
            return BusinessSessionView(
                status = BusinessAuthStatus.DETACHED,
                identityEpoch = 0,
                generation = sessionOrdinal.toLong(),
                attachHandle = "attach-$sessionOrdinal",
            )
        }

        override suspend fun attach(attachHandle: String): BusinessSessionView = when (attachHandle) {
            "attach-1" -> withContext(NonCancellable) {
                staleAttachStarted.complete(Unit)
                releaseStaleAttach.await()
                readySession(identityEpoch = 21)
            }
            "attach-2" -> readySession(identityEpoch = 22)
            else -> error("Unexpected attach handle")
        }

        override suspend fun tenantCandidates(account: String): List<RpcBusinessTenantCandidate> = emptyList()
        override suspend fun login(account: String, password: CharArray, candidateId: String): BusinessSessionView =
            error("login must not be called")
        override suspend fun restore(): BusinessSessionView = error("restore must not be called")
        override suspend fun logout(): BusinessSessionView =
            BusinessSessionView(BusinessAuthStatus.SIGNED_OUT, identityEpoch = 0, generation = 0)
    }

    private inner class DelayedReconciliationClient(
        private val result: BusinessSessionView,
    ) : BusinessAuthClient {
        val sessionStarted = CompletableDeferred<Unit>()
        val releaseSession = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()

        override suspend fun session(): BusinessSessionView {
            calls += "session"
            sessionStarted.complete(Unit)
            releaseSession.await()
            return result
        }

        override suspend fun attach(attachHandle: String): BusinessSessionView = error("attach must not be called")

        override suspend fun tenantCandidates(account: String): List<RpcBusinessTenantCandidate> = emptyList()

        override suspend fun login(
            account: String,
            password: CharArray,
            candidateId: String,
        ): BusinessSessionView = error("login must not be called")

        override suspend fun restore(): BusinessSessionView = error("restore must not be called")

        override suspend fun logout(): BusinessSessionView {
            calls += "logout"
            return signedOutSession(generation = result.generation)
        }
    }

    private inner class ReorderedStartupReconciliationClient : BusinessAuthClient {
        val startupProbeStarted = CompletableDeferred<Unit>()
        val releaseStartupProbe = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()

        override suspend fun session(): BusinessSessionView {
            calls += "session"
            return when (calls.count { it == "session" }) {
                1 -> withContext(NonCancellable) {
                    startupProbeStarted.complete(Unit)
                    releaseStartupProbe.await()
                    readySession(identityEpoch = 1, generation = 3)
                }
                2, 3 -> signedOutSession(generation = 4)
                else -> error("unexpected session probe")
            }
        }

        override suspend fun attach(attachHandle: String): BusinessSessionView = error("attach must not be called")

        override suspend fun tenantCandidates(account: String): List<RpcBusinessTenantCandidate> = emptyList()

        override suspend fun login(
            account: String,
            password: CharArray,
            candidateId: String,
        ): BusinessSessionView = error("login must not be called")

        override suspend fun restore(): BusinessSessionView = error("restore must not be called")

        override suspend fun logout(): BusinessSessionView {
            calls += "logout"
            return signedOutSession(generation = 4)
        }
    }

    private inner class SequencedStartupSessionClient(
        private vararg val sessions: BusinessSessionView,
    ) : BusinessAuthClient {
        val calls = mutableListOf<String>()
        private var sessionIndex = 0

        override suspend fun session(): BusinessSessionView {
            calls += "session"
            return sessions[sessionIndex++]
        }

        override suspend fun attach(attachHandle: String): BusinessSessionView = error("attach must not be called")

        override suspend fun tenantCandidates(account: String): List<RpcBusinessTenantCandidate> = emptyList()

        override suspend fun login(
            account: String,
            password: CharArray,
            candidateId: String,
        ): BusinessSessionView = error("login must not be called")

        override suspend fun restore(): BusinessSessionView = error("restore must not be called")

        override suspend fun logout(): BusinessSessionView {
            calls += "logout"
            return signedOutSession(generation = sessions.last().generation)
        }
    }
}
