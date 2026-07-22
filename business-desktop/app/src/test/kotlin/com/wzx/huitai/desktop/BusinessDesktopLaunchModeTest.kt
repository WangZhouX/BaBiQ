package com.wzx.huitai.desktop

import com.wzx.huitai.desktop.app.BusinessAgentLaunchMode
import kotlin.test.Test
import kotlin.test.assertEquals

class BusinessDesktopLaunchModeTest {
    @Test
    fun `frontend development switch selects external backend mode`() {
        assertEquals(
            BusinessAgentLaunchMode.ExternalDevelopment,
            resolveBusinessAgentLaunchMode(
                mapOf("HUITAI_DESKTOP_EXTERNAL_BACKEND" to "1"),
            ),
        )
        assertEquals(BusinessAgentLaunchMode.Embedded, resolveBusinessAgentLaunchMode(emptyMap()))
    }
}
