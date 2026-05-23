package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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
	LazyColumn(
		modifier = modifier.fillMaxSize(),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		items(messages, key = { it.id }) { message ->
			// 每条消息的具体样式交给 MessageBubble，列表只关心排序和布局。
			MessageBubble(message)
		}
	}
}
