package com.wzx.huitai.desktop.ui.layout

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BusinessDesktopLayoutPolicyTest {
    @Test
    fun `wide mode keeps fixed rails and at least 560 dp form`() {
        val layout = BusinessDesktopLayoutPolicy.resolve(1280.dp)

        assertEquals(BusinessDesktopLayoutMode.WIDE, layout.mode)
        assertEquals(210.dp, layout.navigationWidth)
        assertEquals(420.dp, layout.agentWidth)
        assertTrue(layout.formWidth >= 560.dp)
        assertEquals(1280.dp, layout.navigationWidth + layout.formWidth + layout.agentWidth)
    }

    @Test
    fun `medium mode keeps fixed rails and at least 560 dp form`() {
        val layout = BusinessDesktopLayoutPolicy.resolve(1024.dp)

        assertEquals(BusinessDesktopLayoutMode.MEDIUM, layout.mode)
        assertEquals(72.dp, layout.navigationWidth)
        assertEquals(360.dp, layout.agentWidth)
        assertTrue(layout.formWidth >= 560.dp)
        assertEquals(1024.dp, layout.navigationWidth + layout.formWidth + layout.agentWidth)
    }

    @Test
    fun `compact mode gives the shell one full width content slot`() {
        val layout = BusinessDesktopLayoutPolicy.resolve(900.dp)

        assertEquals(BusinessDesktopLayoutMode.COMPACT, layout.mode)
        assertEquals(900.dp, layout.formWidth)
        assertEquals(0.dp, layout.agentWidth)
    }

    @Test
    fun `invalid or tiny widths never produce negative or overlapping slots`() {
        listOf((-10).dp, 0.dp, 1.dp, 1023.dp, 1279.dp, 1600.dp).forEach { width ->
            val layout = BusinessDesktopLayoutPolicy.resolve(width)

            assertTrue(layout.navigationWidth >= 0.dp)
            assertTrue(layout.formWidth >= 0.dp)
            assertTrue(layout.agentWidth >= 0.dp)
            assertTrue(layout.navigationWidth + layout.formWidth + layout.agentWidth <= width.coerceAtLeast(0.dp))
        }
    }
}
