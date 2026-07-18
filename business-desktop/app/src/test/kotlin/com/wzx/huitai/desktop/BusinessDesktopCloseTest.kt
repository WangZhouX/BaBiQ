package com.wzx.huitai.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BusinessDesktopCloseTest {
    @Test
    fun `window close shuts down the composition before canceling runtime and exiting`() {
        val calls = mutableListOf<String>()

        val failure = closeBusinessDesktop(
            shutdown = { calls += "shutdown" },
            cancelRuntime = { calls += "cancel" },
            exitApplication = { calls += "exit" },
        )

        assertNull(failure)
        assertEquals(listOf("shutdown", "cancel", "exit"), calls)
    }

    @Test
    fun `window close still cancels runtime and exits when shutdown fails`() {
        val calls = mutableListOf<String>()
        val expected = IllegalStateException("shutdown failed")

        val failure = closeBusinessDesktop(
            shutdown = {
                calls += "shutdown"
                throw expected
            },
            cancelRuntime = { calls += "cancel" },
            exitApplication = { calls += "exit" },
        )

        assertSame(expected, failure)
        assertEquals(listOf("shutdown", "cancel", "exit"), calls)
    }
}
