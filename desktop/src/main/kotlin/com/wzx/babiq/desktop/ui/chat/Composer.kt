package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 底部输入区。
 *
 * 这里包含三件事：用户输入文本、工作区/权限/模型上下文条、发送按钮。
 */
@Composable
fun Composer(
	state: AppState,
	onSend: (String) -> Unit,
	onSelectWorkspace: (String) -> Unit,
	onSelectProvider: (String, String?) -> Unit,
	onChangeSandboxMode: (String) -> Unit,
) {
	// 本地 text 是输入框即时状态；state.draft 变化时重新同步，保证断线保留草稿能回填到输入框。
	var text by remember(state.draft) { mutableStateOf(state.draft) }
	val chrome = composerChromeFor(text = text, canSend = state.canSend)
	val inputInteractionSource = remember { MutableInteractionSource() }
	val inputFocused by inputInteractionSource.collectIsFocusedAsState()
	val composerShape = RoundedCornerShape(18.dp)
	val inputShape = RoundedCornerShape(14.dp)

	Card(
		shape = composerShape,
		border = BorderStroke(1.dp, if (inputFocused) BaBiQColors.Accent.copy(alpha = 0.28f) else BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Panel),
		elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
	) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(10.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			val slashSuggestions = slashCommandSuggestionsFor(text)

			BasicTextField(
				value = text,
				onValueChange = { text = it },
				enabled = state.canSend,
				textStyle = MaterialTheme.typography.bodyLarge.copy(
					color = if (state.canSend) BaBiQColors.Ink else BaBiQColors.Muted,
				),
				interactionSource = inputInteractionSource,
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(min = 104.dp)
					.background(Color(0xFFFCFCFA), inputShape)
					.border(
						BorderStroke(
							1.dp,
							if (inputFocused) BaBiQColors.Accent.copy(alpha = 0.72f) else Color(0xFFE8E6E1),
						),
						inputShape,
					)
					.onPreviewKeyEvent { event ->
						// Enter 发送，Shift+Enter 换行，符合常见聊天/代码助手输入习惯。
						if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
							if (chrome.sendEnabled) {
								onSend(text)
								text = ""
							}
							true
						} else {
							false
						}
					},
				minLines = 3,
				decorationBox = { innerTextField ->
					Box(
						modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 14.dp),
					) {
						if (text.isEmpty()) {
							Text(
								text = chrome.placeholder,
								style = MaterialTheme.typography.bodyLarge,
								color = BaBiQColors.Muted.copy(alpha = 0.78f),
							)
						}
						innerTextField()
					}
				},
			)
			SlashCommandMenu(
				suggestions = slashSuggestions,
				onSelect = { suggestion -> text = suggestion.template },
			)
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				ComposerContextBar(
					state = state,
					onSelectWorkspace = onSelectWorkspace,
					onSelectProvider = onSelectProvider,
					onChangeSandboxMode = onChangeSandboxMode,
					modifier = Modifier.weight(1f),
				)
				Button(
					enabled = chrome.sendEnabled,
					shape = RoundedCornerShape(999.dp),
					contentPadding = PaddingValues(horizontal = 22.dp, vertical = 0.dp),
					colors = ButtonDefaults.buttonColors(
						containerColor = BaBiQColors.Accent,
						contentColor = Color.White,
						disabledContainerColor = Color(0xFFE8E6E1),
						disabledContentColor = Color(0xFF9A9DA2),
					),
					modifier = Modifier.height(40.dp),
					onClick = {
						// 点击发送后立即清空本地输入框；如果后端失败，Controller 会把错误展示到 banner。
						onSend(text)
						text = ""
					},
				) {
					Text(chrome.sendLabel)
				}
			}
		}
	}
}

internal data class ComposerChrome(
	val placeholder: String,
	val sendLabel: String,
	val sendEnabled: Boolean,
	val sendTone: ComposerSendTone,
)

internal enum class ComposerSendTone {
	Accent,
	Disabled,
}

internal fun composerChromeFor(text: String, canSend: Boolean): ComposerChrome {
	val sendEnabled = canSend && text.isNotBlank()
	return ComposerChrome(
		placeholder = "描述任务或提出问题",
		sendLabel = "发送",
		sendEnabled = sendEnabled,
		sendTone = if (sendEnabled) ComposerSendTone.Accent else ComposerSendTone.Disabled,
	)
}
