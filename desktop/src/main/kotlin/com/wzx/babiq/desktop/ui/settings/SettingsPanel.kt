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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.ui.common.chooseWorkspaceDirectory
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 设置页。
 *
 * P1-4 只做只读 Provider 信息和工作目录选择，不做 API Key 编辑或 Provider 新增删除。
 */
@Composable
fun SettingsPanel(
	state: AppState,
	onSelectWorkspace: (String) -> Unit,
) {
	Column(
		modifier = Modifier.fillMaxSize().padding(34.dp),
		verticalArrangement = Arrangement.spacedBy(18.dp),
	) {
		Text("设置", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
		SettingsCard("工作区") {
			Text("项目: ${state.workspace.projectName}")
			Text("路径: ${state.workspace.cwd}")
			state.workspace.permissionLabel?.let { label ->
				// 权限展示来自后端 sandbox/policy，和输入框上下文条保持同源。
				Text("权限: $label")
			}
			Button(
				enabled = state.canSwitchWorkspace,
				onClick = {
					// 设置页和输入框上下文条共用同一个目录选择器，保证行为一致。
					chooseWorkspaceDirectory(state.workspace.cwd)?.let(onSelectWorkspace)
				},
			) {
				Text("选择工作目录")
			}
		}
		SettingsCard("Provider 只读信息") {
			if (state.providerState.providers.isEmpty()) {
				Text("当前没有可选择的后端 Provider，请检查后端配置。", color = BaBiQColors.Muted)
			} else {
				// Provider 配置来源是后端 application.yml，P1-4 只展示，不在桌面端写回。
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

/**
 * 设置页里的基础卡片容器。
 */
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
