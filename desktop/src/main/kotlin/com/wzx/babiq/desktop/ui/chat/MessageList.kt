package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
	val listState = rememberLazyListState()
	val scrollTarget = remember(timeline) { messageListScrollTarget(timeline) }
	if (scrollTarget != null) {
		LaunchedEffect(scrollTarget.signature) {
			listState.scrollToItem(scrollTarget.itemIndex)
		}
	}
	LazyColumn(
		modifier = modifier.fillMaxSize(),
		state = listState,
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		items(timeline, key = { it.id }) { item ->
			when (item) {
				is TimelineItem.Message -> MessageBubble(item.message)
				is TimelineItem.Process -> TimelineProcessCard(item)
			}
		}
		item(key = BottomAnchorKey) {
			Spacer(Modifier.height(1.dp))
		}
	}
}

internal data class MessageListScrollTarget(
	val itemIndex: Int,
	val signature: String,
)

internal fun messageListScrollTarget(timeline: List<TimelineItem>): MessageListScrollTarget? {
	val lastItem = timeline.lastOrNull() ?: return null
	return MessageListScrollTarget(
		itemIndex = timeline.size,
		signature = "${timeline.size}|${lastItem.scrollSignature()}",
	)
}

private const val BottomAnchorKey = "message-list-bottom-anchor"

private fun TimelineItem.scrollSignature(): String =
	when (this) {
		is TimelineItem.Message -> message.scrollSignature()
		is TimelineItem.Process -> listOf(
			id,
			title,
			expandedByDefault.toString(),
			rows.joinToString(separator = "\u001F") { row ->
				listOf(row.id, row.status, row.summary, row.detail, row.active.toString(), row.failed.toString())
					.joinToString(separator = "\u001E")
			},
		).joinToString(separator = "\u001D")
	}

private fun ChatMessage.scrollSignature(): String =
	when (this) {
		is ChatMessage.User -> "$id|$text"
		is ChatMessage.Agent -> "$id|$text|$streaming"
		is ChatMessage.Reasoning -> "$id|$text|$completed"
		is ChatMessage.Tool -> "$id|$title|$status|$detail"
		is ChatMessage.FileChange -> "$id|$action|$path|$status|${preview.orEmpty()}"
		is ChatMessage.TurnSummary -> "$id|${summary.status}|${summary.totalTokens}|${summary.toolCalls}|${summary.durationMs}"
	}
