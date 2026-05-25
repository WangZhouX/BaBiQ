package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.ChatMessage
import com.wzx.babiq.desktop.ui.runtime.TurnSummaryBar
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 单条聊天消息的渲染入口。
 *
 * TurnSummary 有专门的运行反馈条；其它消息统一渲染为左右对齐的气泡。
 */
@Composable
fun MessageBubble(message: ChatMessage) {
	when (message) {
		is ChatMessage.TurnSummary -> TurnSummaryBar(message.summary)
		else -> Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = if (message is ChatMessage.User) Arrangement.End else Arrangement.Start,
		) {
			Column(
				modifier = Modifier
					.widthIn(max = 680.dp)
					.background(backgroundFor(message), RoundedCornerShape(8.dp))
					.padding(12.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
				horizontalAlignment = Alignment.Start,
			) {
				val title = titleFor(message)
				if (title.isNotBlank()) {
					// 普通聊天气泡不再显示“你 / BaBiQ”，只给工具和文件卡片保留语义标题，降低对话区噪音。
					Text(title, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
				}
				Text(bodyFor(message), style = bodyStyleFor(message))
			}
		}
	}
}

/** 根据消息类型选择气泡背景色。 */
private fun backgroundFor(message: ChatMessage): Color =
	when (message) {
		is ChatMessage.User -> Color(0xFFE4ECF7)
		is ChatMessage.Tool, is ChatMessage.FileChange -> Color(0xFFF1EFE8)
		else -> BaBiQColors.Panel
	}

/** 根据消息类型选择气泡标题。 */
private fun titleFor(message: ChatMessage): String =
	when (message) {
		is ChatMessage.User -> ""
		is ChatMessage.Agent -> ""
		is ChatMessage.Tool -> "工具 · ${message.status}"
		is ChatMessage.FileChange -> "文件 · ${message.status}"
		is ChatMessage.TurnSummary -> "摘要"
	}

/** 根据消息类型选择气泡正文。 */
private fun bodyFor(message: ChatMessage): String =
	when (message) {
		is ChatMessage.User -> message.text
		is ChatMessage.Agent -> message.text
		is ChatMessage.Tool -> "${message.title}\n${message.detail}"
		is ChatMessage.FileChange -> "${message.action}: ${message.path}\n${message.preview.orEmpty()}"
		is ChatMessage.TurnSummary -> ""
	}

/** 工具和文件变更使用等宽字体，便于阅读命令、路径和输出。 */
@Composable
private fun bodyStyleFor(message: ChatMessage) =
	if (message is ChatMessage.Tool || message is ChatMessage.FileChange) {
		MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
	} else {
		MaterialTheme.typography.bodyMedium
	}
