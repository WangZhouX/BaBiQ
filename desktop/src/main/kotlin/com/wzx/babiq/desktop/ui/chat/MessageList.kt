package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.ChatMessage

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
			MessageBubble(message)
		}
	}
}
