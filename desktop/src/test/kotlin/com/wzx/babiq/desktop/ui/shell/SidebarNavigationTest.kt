package com.wzx.babiq.desktop.ui.shell

import com.wzx.babiq.desktop.state.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SidebarNavigationTest {

	@Test
	fun `侧边栏搜索入口指向搜索工作台且保持可点击`() {
		val items = sidebarNavigationItems()
		val search = items.single { it.label == "搜索" }

		assertTrue(search.enabled)
		assertEquals(Screen.Search, search.screen)
		assertTrue(items.none { it.label.contains("交互总览") })
	}
}
