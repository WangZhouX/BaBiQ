package com.wzx.babiq.desktop.ui.chat

import androidx.compose.ui.Alignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopStatusLineLayoutTest {

	@Test
	fun `顶部状态入口应占满聊天主区并靠右展示`() {
		val spec = topStatusLineLayoutSpec()

		assertTrue(spec.fillMaxWidth)
		assertEquals(Alignment.End, spec.horizontalArrangement)
	}
}
