package com.wzx.babiq.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class MainWindowStateTest {

	@Test
	fun `default desktop window size follows Codex reference canvas`() {
		assertEquals(DpSize(1920.dp, 860.dp), DefaultWindowSize)
	}
}
