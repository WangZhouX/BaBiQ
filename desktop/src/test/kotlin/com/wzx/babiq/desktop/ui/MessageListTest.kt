package com.wzx.babiq.desktop.ui

import com.wzx.babiq.desktop.state.ChatMessage
import com.wzx.babiq.desktop.ui.chat.deriveTurnTimeline
import com.wzx.babiq.desktop.ui.chat.messageListScrollTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MessageListTest {
	@Test
	fun `scroll target points at bottom anchor after the last timeline item`() {
		val timeline = deriveTurnTimeline(
			listOf(
				ChatMessage.User("u1", "start"),
				ChatMessage.Agent("a1", "done"),
			),
		)

		val target = messageListScrollTarget(timeline)

		assertEquals(timeline.size, target?.itemIndex)
	}

	@Test
	fun `scroll target changes when running process content grows without a new item`() {
		val before = deriveTurnTimeline(
			listOf(
				ChatMessage.User("u1", "run"),
				ChatMessage.Reasoning("r1", "first line", completed = false),
			),
		)
		val after = deriveTurnTimeline(
			listOf(
				ChatMessage.User("u1", "run"),
				ChatMessage.Reasoning("r1", "first line\nsecond line", completed = false),
			),
		)

		assertEquals(before.last().id, after.last().id)
		assertNotEquals(messageListScrollTarget(before)?.signature, messageListScrollTarget(after)?.signature)
	}
}
