package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

	Card(
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Panel),
	) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			OutlinedTextField(
				value = text,
				onValueChange = { text = it },
				enabled = state.canSend,
				placeholder = { Text("描述任务或提出问题") },
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(min = 88.dp)
					.onPreviewKeyEvent { event ->
						// Enter 发送，Shift+Enter 换行，符合常见聊天/代码助手输入习惯。
						if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
							onSend(text)
							text = ""
							true
						} else {
							false
						}
					},
				minLines = 3,
			)
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				ComposerContextBar(
					state = state,
					onSelectWorkspace = onSelectWorkspace,
					onSelectProvider = onSelectProvider,
					onChangeSandboxMode = onChangeSandboxMode,
				)
				Button(
					enabled = state.canSend && text.isNotBlank(),
					onClick = {
						// 点击发送后立即清空本地输入框；如果后端失败，Controller 会把错误展示到 banner。
						onSend(text)
						text = ""
					},
				) {
					Text("发送")
				}
			}
		}
	}
}
