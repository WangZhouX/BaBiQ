package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 输入框里的显式斜杠命令提示项。
 *
 * slash 命令只负责创建或复用编排/团队工作容器，不会直接开始执行。
 */
data class SlashCommandSuggestion(
	val command: String,
	val title: String,
	val description: String,
	val template: String,
)

private val slashCommandSuggestions = listOf(
	SlashCommandSuggestion(
		command = "/编排",
		title = "创建编排",
		description = "创建或复用一个可重复完成目标的编排容器",
		template = "/编排 名称：目标",
	),
	SlashCommandSuggestion(
		command = "/团队",
		title = "创建团队",
		description = "创建或复用一个可重复完成目标的团队协作容器",
		template = "/团队 名称：目标",
	),
)

fun slashCommandSuggestionsFor(input: String): List<SlashCommandSuggestion> {
	val text = input.trimStart()
	if (!text.startsWith("/")) {
		return emptyList()
	}
	if (text.contains('\n') || text.contains(':') || text.contains('：')) {
		return emptyList()
	}

	val query = text.removePrefix("/").trim()
	if (query.isBlank()) {
		return slashCommandSuggestions
	}

	return slashCommandSuggestions.filter { suggestion ->
		suggestion.command.removePrefix("/").contains(query, ignoreCase = true) ||
			suggestion.title.contains(query, ignoreCase = true) ||
			suggestion.description.contains(query, ignoreCase = true)
	}
}

@Composable
fun SlashCommandMenu(
	suggestions: List<SlashCommandSuggestion>,
	onSelect: (SlashCommandSuggestion) -> Unit,
) {
	if (suggestions.isEmpty()) {
		return
	}

	Card(
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Background),
	) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Text(
				text = "命令",
				modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
				color = BaBiQColors.Muted,
			)
			suggestions.forEach { suggestion ->
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clickable { onSelect(suggestion) }
						.padding(horizontal = 12.dp, vertical = 8.dp),
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					Text(
						text = suggestion.command,
						fontWeight = FontWeight.SemiBold,
						color = BaBiQColors.Ink,
					)
					Text(
						text = suggestion.description,
						color = BaBiQColors.Muted,
					)
				}
			}
		}
	}
}
