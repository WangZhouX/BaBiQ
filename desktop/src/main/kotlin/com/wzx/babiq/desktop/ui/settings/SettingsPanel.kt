package com.wzx.babiq.desktop.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

@Composable
fun SettingsPanel(state: AppState) {
	Column(
		modifier = Modifier.fillMaxSize().padding(34.dp),
		verticalArrangement = Arrangement.spacedBy(18.dp),
	) {
		Text("设置", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
		SettingsCard("工作区") {
			Text("项目: ${state.workspace.projectName}")
			Text("路径: ${state.workspace.cwd}")
			Text("模式: ${state.workspace.mode}")
			Text("权限: ${state.workspace.permission}")
		}
		SettingsCard("Provider 只读信息") {
			if (state.providerState.providers.isEmpty()) {
				Text("当前没有可选择的后端 Provider，请检查后端配置。", color = BaBiQColors.Muted)
			} else {
				state.providerState.providers.forEach { provider ->
					Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
						Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							Text(provider.label, fontWeight = FontWeight.Medium)
							if (provider.active) Text("active", color = BaBiQColors.Success)
						}
						Text("id: ${provider.id}", color = BaBiQColors.Muted)
						Text(
							text = provider.models.joinToString { it.label }.ifBlank { "models: 后端暂未返回" },
							color = BaBiQColors.Muted,
						)
					}
				}
			}
		}
		state.providerState.error?.let {
			Text("Provider 错误: $it", color = BaBiQColors.Danger)
		}
	}
}

@Composable
private fun SettingsCard(
	title: String,
	content: @Composable ColumnScope.() -> Unit,
) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Panel),
	) {
		Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
			content()
		}
	}
}
