package com.wzx.huitai.desktop.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class BusinessLogoutControllerTest {
    @Test
    fun `logout clears login password before revoking the authenticated session`() = runTest {
        val events = mutableListOf<String>()
        val controller = BusinessLogoutController(
            logout = {
                events += "logout"
            },
            clearSensitiveInput = {
                events += "clear"
            },
        )

        controller.logout()

        assertEquals(listOf("clear", "logout"), events)
    }

    @Test
    fun `logout failure still leaves sensitive login input cleared`() = runTest {
        var clearCount = 0
        val controller = BusinessLogoutController(
            logout = { error("remote failure") },
            clearSensitiveInput = { clearCount += 1 },
        )

        assertFailsWith<IllegalStateException> { controller.logout() }
        assertEquals(1, clearCount)
    }
}
