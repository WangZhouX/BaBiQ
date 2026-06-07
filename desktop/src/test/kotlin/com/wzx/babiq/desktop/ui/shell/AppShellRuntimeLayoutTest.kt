package com.wzx.babiq.desktop.ui.shell

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppShellRuntimeLayoutTest {

	@Test
	fun `runtime panel width follows horizontal resize handle direction`() {
		assertEquals(440.dp, resizeRuntimePanelWidth(360.dp, (-80).dp))
		assertEquals(320.dp, resizeRuntimePanelWidth(360.dp, 80.dp))
	}

	@Test
	fun `runtime panel width is clamped to usable bounds`() {
		assertEquals(MinRuntimePanelWidth, resizeRuntimePanelWidth(360.dp, 500.dp))
		assertEquals(760.dp, resizeRuntimePanelWidth(360.dp, (-500).dp))
	}

	@Test
	fun `runtime panel resize handle keeps a visible drag target`() {
		assertTrue(RuntimePanelResizeHandleWidth >= 18.dp)
		assertTrue(RuntimePanelResizeRailWidth >= 4.dp)
	}
}
