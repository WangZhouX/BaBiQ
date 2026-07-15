package com.wzx.huitai.integration.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthenticationStateMachineTest {
    private val machine = AuthenticationStateMachine()

    private val allowedTransitions = setOf(
        AuthenticationState.SIGNED_OUT to AuthenticationState.SIGNING_IN,
        AuthenticationState.SIGNING_IN to AuthenticationState.AUTHENTICATED,
        AuthenticationState.SIGNING_IN to AuthenticationState.EXPIRED,
        AuthenticationState.SIGNING_IN to AuthenticationState.MEMBERSHIP_EXPIRED,
        AuthenticationState.SIGNING_IN to AuthenticationState.SIGNED_OUT,
        AuthenticationState.AUTHENTICATED to AuthenticationState.REFRESHING,
        AuthenticationState.AUTHENTICATED to AuthenticationState.SWITCHING_TENANT,
        AuthenticationState.AUTHENTICATED to AuthenticationState.SIGNED_OUT,
        AuthenticationState.REFRESHING to AuthenticationState.AUTHENTICATED,
        AuthenticationState.REFRESHING to AuthenticationState.EXPIRED,
        AuthenticationState.REFRESHING to AuthenticationState.MEMBERSHIP_EXPIRED,
        AuthenticationState.REFRESHING to AuthenticationState.SIGNED_OUT,
        AuthenticationState.SWITCHING_TENANT to AuthenticationState.AUTHENTICATED,
        AuthenticationState.SWITCHING_TENANT to AuthenticationState.EXPIRED,
        AuthenticationState.SWITCHING_TENANT to AuthenticationState.MEMBERSHIP_EXPIRED,
        AuthenticationState.SWITCHING_TENANT to AuthenticationState.SIGNED_OUT,
        AuthenticationState.EXPIRED to AuthenticationState.SIGNED_OUT,
        AuthenticationState.MEMBERSHIP_EXPIRED to AuthenticationState.SIGNED_OUT,
    )

    @Test
    fun `规格列出的认证状态迁移全部允许`() {
        allowedTransitions.forEach { (from, to) ->
            assertEquals(to, machine.transition(from, to), message = "$from -> $to")
        }
    }

    @Test
    fun `规格未列出的认证状态迁移全部拒绝`() {
        val allTransitions = AuthenticationState.entries.flatMap { from ->
            AuthenticationState.entries.map { to -> from to to }
        }

        (allTransitions - allowedTransitions).forEach { (from, to) ->
            assertFailsWith<IllegalArgumentException>(message = "$from -> $to") {
                machine.transition(from, to)
            }
        }
    }
}
