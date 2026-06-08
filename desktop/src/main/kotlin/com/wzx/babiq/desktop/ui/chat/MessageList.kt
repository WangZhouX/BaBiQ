package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.ChatMessage

/**
 * 聊天消息列表。
 *
 * LazyColumn 只渲染可见项；key 使用消息 id，能让 item/updated 时保持滚动和动画更稳定。
 */
@Composable
fun MessageList(
	messages: List<ChatMessage>,
	modifier: Modifier = Modifier,
) {
	val timeline = remember(messages) { deriveTurnTimeline(messages) }
	LazyColumn(
		modifier = modifier.fillMaxSize(),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		items(timeline, key = { it.id }) { item ->
			when (item) {
				is TimelineItem.Message -> MessageBubble(item.message)
				is TimelineItem.Process -> TimelineProcessCard(item)
			}
		}
	}
}
