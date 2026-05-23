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

@Composable
fun Composer(
	state: AppState,
	onSend: (String) -> Unit,
	onSelectWorkspace: (String) -> Unit,
	onSelectProvider: (String, String?) -> Unit,
) {
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
				)
				Button(
					enabled = state.canSend && text.isNotBlank(),
					onClick = {
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
