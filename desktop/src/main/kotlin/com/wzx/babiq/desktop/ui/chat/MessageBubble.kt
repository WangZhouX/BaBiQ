package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
				// completed 工具默认收起，避免把 stdout/stderr 或 spotlighting 原文铺满聊天区；点击标题可临时展开查看。
				var expanded by remember(message.id, (message as? ChatMessage.Tool)?.status) {
					mutableStateOf(!shouldCollapseByDefault(message))
				}
				val title = titleFor(message)
				if (title.isNotBlank()) {
					// 普通聊天气泡不再显示“你 / BaBiQ”，只给工具和文件卡片保留语义标题，降低对话区噪音。
					MessageTitle(
						title = title,
						expandable = message is ChatMessage.Tool && !message.isContextEvent() && message.detail.isNotBlank(),
						expanded = expanded,
						onToggle = { expanded = !expanded },
					)
				}
				SelectionContainer {
					Text(bodyFor(message, expanded), style = bodyStyleFor(message))
				}
			}
		}
	}
}

/**
 * 工具标题承担“展开 / 收起”的交互入口。
 *
 * 原型里完成后的工具调用是折叠摘要而不是完整输出；这里把交互限制在标题行，
 * 让正文仍然可以被 SelectionContainer 正常选择复制。
 */
@Composable
private fun MessageTitle(
	title: String,
	expandable: Boolean,
	expanded: Boolean,
	onToggle: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.then(if (expandable) Modifier.clickable(onClick = onToggle) else Modifier),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(title, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
		if (expandable) {
			Text(
				if (expanded) "收起" else "展开",
				style = MaterialTheme.typography.labelSmall,
				color = BaBiQColors.Muted,
			)
		}
	}
}

/** 根据消息类型选择气泡背景色。 */
private fun backgroundFor(message: ChatMessage): Color =
	when (message) {
		is ChatMessage.User -> Color(0xFFE4ECF7)
		is ChatMessage.Tool if message.isContextEvent() -> Color(0xFFEAF3EF)
		is ChatMessage.Tool, is ChatMessage.FileChange -> Color(0xFFF1EFE8)
		else -> BaBiQColors.Panel
	}

/** 根据消息类型选择气泡标题。 */
private fun titleFor(message: ChatMessage): String =
	when (message) {
		is ChatMessage.User -> ""
		is ChatMessage.Agent -> ""
		is ChatMessage.Tool if message.isContextEvent() -> "上下文 · ${message.status}"
		is ChatMessage.Tool -> "工具 · ${message.status}"
		is ChatMessage.FileChange -> "文件 · ${message.status}"
		is ChatMessage.TurnSummary -> "摘要"
	}

/** 根据消息类型选择气泡正文。 */
private fun bodyFor(message: ChatMessage): String =
	bodyFor(message, expanded = false)

/**
 * 根据消息类型选择气泡正文。
 *
 * completed 工具在默认视图只显示工具摘要；完整输出仍保留在展开态和运行详情中，
 * 这样既符合原型收起态，也避免把不受信任工具输出混入普通对话阅读流。
 */
private fun bodyFor(message: ChatMessage, expanded: Boolean): String =
	when (message) {
		is ChatMessage.User -> message.text
		is ChatMessage.Agent -> message.text
		is ChatMessage.Tool if message.isContextEvent() -> message.detail
		is ChatMessage.Tool -> if (shouldCollapseByDefault(message) && !expanded) {
			message.title
		} else {
			"${message.title}\n${message.detail}".trimEnd()
		}
		is ChatMessage.FileChange -> "${message.action}: ${message.path}\n${message.preview.orEmpty()}"
		is ChatMessage.TurnSummary -> ""
	}

/** 工具和文件变更使用等宽字体，便于阅读命令、路径和输出。 */
@Composable
private fun bodyStyleFor(message: ChatMessage) =
	if ((message is ChatMessage.Tool && !message.isContextEvent()) || message is ChatMessage.FileChange) {
		MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
	} else {
		MaterialTheme.typography.bodyMedium
	}

/**
 * P3 目前只有 contextCompaction 会作为聊天流中的上下文治理事件进入 UI。
 *
 * 它虽然复用 Tool 模型承载，但语义上不是一次用户触发的工具调用，所以用上下文卡片展示，
 * 避免用户误以为 Agent 额外执行了工具。
 */
private fun ChatMessage.Tool.isContextEvent(): Boolean = title == "上下文压缩"

/** 工具完成后默认折叠，运行中或失败时保留详细输出帮助判断状态。 */
private fun shouldCollapseByDefault(message: ChatMessage): Boolean =
	message is ChatMessage.Tool &&
		!message.isContextEvent() &&
		message.status.equals("completed", ignoreCase = true)
