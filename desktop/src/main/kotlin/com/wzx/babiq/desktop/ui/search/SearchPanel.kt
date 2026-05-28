package com.wzx.babiq.desktop.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.CapabilityInfo
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.ui.settings.capabilityExampleQueries
import com.wzx.babiq.desktop.ui.settings.groupCapabilitiesForCenter
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 搜索工作台的顶部摘要模型。
 *
 * 这个模型把长期记忆、能力目录和 Skill metadata 的加载结果压成几条稳定文案，
 * 让 Composable 只关心排版，后续测试也可以不启动 Compose 运行时。
 *
 * @property memoryLabel 长期记忆状态摘要。
 * @property memoryResultLabel 最近一次 memory/search 结果摘要。
 * @property capabilityLabel 能力目录状态摘要。
 * @property skillLabel 本地 Skill metadata 摘要。
 */
data class SearchWorkbenchModel(
	val memoryLabel: String,
	val memoryResultLabel: String,
	val capabilityLabel: String,
	val skillLabel: String,
)

/**
 * 从 AppState 构造搜索工作台摘要。
 *
 * 这里读取的都是后端已经落入 AppState 的真实状态；函数本身不触发网络请求，
 * 网络请求由 ChatController 在打开 Screen.Search 或点击搜索按钮时负责。
 */
fun buildSearchWorkbenchModel(state: AppState): SearchWorkbenchModel {
	val memory = state.memoryState.status
	val capability = state.capabilityState.status
	return SearchWorkbenchModel(
		memoryLabel = when {
			memory == null -> "长期记忆: 未加载"
			memory.enabled -> "长期记忆: 已启用 · CLEAN ${memory.cleanCandidateCount} · G${memory.phase2Generation}"
			else -> "长期记忆: 已关闭"
		},
		memoryResultLabel = if (state.memoryState.searchResults.isEmpty()) {
			"记忆检索: 暂无结果"
		} else {
			"记忆检索: ${state.memoryState.searchResults.size} 条 · ${state.memoryState.searchStrategy ?: "未知策略"} · ${state.memoryState.searchTokenEstimate} token"
		},
		capabilityLabel = if (capability == null) {
			"能力目录: 未加载"
		} else {
			"能力目录: ${capability.totalCount} 个 · 常驻 ${capability.visibleCount} · 按需 ${capability.deferredCount}"
		},
		skillLabel = "Skill: ${state.skillState.skills.size} 个 metadata",
	)
}

/**
 * Figma P3 的搜索/能力类真实产品页。
 *
 * 00 交互总览只是原型索引页，不在这里渲染；本页面承接 P3 06、P3 07、P3 08 的产品能力：
 * 长期记忆检索、中文能力搜索、能力中心详情和 Skill metadata 摘要。
 */
@Composable
fun SearchPanel(
	state: AppState,
	onSearchMemory: (String) -> Unit,
	onSaveCapabilitySettings: (String, Boolean?, String?) -> Unit,
	onSearchCapabilities: (String) -> Unit,
) {
	val model = buildSearchWorkbenchModel(state)
	var memoryQuery by remember(state.memoryState.searchQuery) { mutableStateOf(state.memoryState.searchQuery) }
	var capabilityQuery by remember { mutableStateOf("") }

	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(34.dp),
		verticalArrangement = Arrangement.spacedBy(18.dp),
	) {
		Text("搜索", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
		state.memoryState.error?.let { Text("长期记忆错误: $it", color = BaBiQColors.Danger) }
		state.capabilityState.error?.let { Text("能力错误: $it", color = BaBiQColors.Danger) }
		state.skillState.error?.let { Text("Skill 错误: $it", color = BaBiQColors.Danger) }
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			SearchPill(model.memoryLabel)
			SearchPill(model.memoryResultLabel)
			SearchPill(model.capabilityLabel)
			SearchPill(model.skillLabel)
		}

		SearchCard("长期记忆检索") {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
				OutlinedTextField(
					value = memoryQuery,
					onValueChange = { memoryQuery = it },
					modifier = Modifier.weight(1f),
					singleLine = true,
					label = { Text("记忆 query") },
				)
				Button(enabled = memoryQuery.isNotBlank() && !state.memoryState.loading, onClick = { onSearchMemory(memoryQuery) }) {
					Text("检索")
				}
			}
			if (state.memoryState.searchResults.isEmpty()) {
				Text("暂无记忆引用", color = BaBiQColors.Muted)
			} else {
				state.memoryState.searchResults.take(6).forEach { reference ->
					Text("${reference.confidence} · ${reference.artifactId} · ${reference.tokenEstimate} token", fontWeight = FontWeight.Medium)
					Text(reference.text.take(220), color = BaBiQColors.Muted)
				}
			}
		}

		SearchCard("中文能力搜索") {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
				OutlinedTextField(
					value = capabilityQuery,
					onValueChange = { capabilityQuery = it },
					modifier = Modifier.weight(1f),
					singleLine = true,
					label = { Text("能力 query") },
				)
				Button(enabled = capabilityQuery.isNotBlank() && !state.capabilityState.loading, onClick = { onSearchCapabilities(capabilityQuery) }) {
					Text("搜索")
				}
			}
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				capabilityExampleQueries().forEach { example ->
					OutlinedButton(
						enabled = !state.capabilityState.loading,
						onClick = {
							capabilityQuery = example
							onSearchCapabilities(example)
						},
					) {
						Text(example)
					}
				}
			}
			CapabilityResultList(
				items = state.capabilityState.searchResults.ifEmpty { state.capabilityState.status?.capabilities.orEmpty() },
				canEdit = state.canEditSettings,
				onSaveCapabilitySettings = onSaveCapabilitySettings,
			)
		}

		SearchCard("能力中心") {
			val groups = groupCapabilitiesForCenter(state.capabilityState.status?.capabilities.orEmpty())
			Text(model.capabilityLabel, color = BaBiQColors.Muted)
			CapabilityGroupSummary("Local 工具", groups.local, state.canEditSettings, onSaveCapabilitySettings)
			CapabilityGroupSummary("MCP 工具", groups.mcp, state.canEditSettings, onSaveCapabilitySettings)
			CapabilityGroupSummary("Skill 能力", groups.skills, state.canEditSettings, onSaveCapabilitySettings)
		}

		SearchCard("Skill") {
			if (state.skillState.skills.isEmpty()) {
				Text("暂无 Skill metadata", color = BaBiQColors.Muted)
			} else {
				state.skillState.skills.take(8).forEach { skill ->
					Text(skill.name, fontWeight = FontWeight.Medium)
					Text("${skill.id} · ${skill.namespace}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = BaBiQColors.Muted)
					Text(skill.description.take(160), color = BaBiQColors.Muted)
				}
			}
		}
	}
}

@Composable
private fun SearchPill(text: String) {
	Card(
		shape = RoundedCornerShape(16.dp),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Panel),
		border = BorderStroke(1.dp, BaBiQColors.Border),
	) {
		Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
	}
}

@Composable
private fun SearchCard(
	title: String,
	content: @Composable ColumnScope.() -> Unit,
) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Panel),
	) {
		Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
			content()
		}
	}
}

@Composable
private fun CapabilityResultList(
	items: List<CapabilityInfo>,
	canEdit: Boolean,
	onSaveCapabilitySettings: (String, Boolean?, String?) -> Unit,
) {
	if (items.isEmpty()) {
		Text("暂无能力结果", color = BaBiQColors.Muted)
		return
	}
	items.take(8).forEach { item ->
		CapabilityRow(item, canEdit, onSaveCapabilitySettings)
	}
}

@Composable
private fun CapabilityGroupSummary(
	title: String,
	items: List<CapabilityInfo>,
	canEdit: Boolean,
	onSaveCapabilitySettings: (String, Boolean?, String?) -> Unit,
) {
	if (items.isEmpty()) {
		return
	}
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text("$title · ${items.size}", fontWeight = FontWeight.Bold)
		items.take(4).forEach { item -> CapabilityRow(item, canEdit, onSaveCapabilitySettings) }
	}
}

@Composable
private fun CapabilityRow(
	item: CapabilityInfo,
	canEdit: Boolean,
	onSaveCapabilitySettings: (String, Boolean?, String?) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
		Text(item.displayName, fontWeight = FontWeight.Medium)
		Text(
			"${item.capabilityId} · ${item.type} · ${item.exposureMode}",
			style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
			color = BaBiQColors.Muted,
		)
		Text(item.description.take(160), color = BaBiQColors.Muted)
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			CapabilityActionButton("启用:${if (item.enabled) "开" else "关"}", item.enabled, canEdit) {
				onSaveCapabilitySettings(item.capabilityId, !item.enabled, null)
			}
			listOf("VISIBLE", "DEFERRED", "DISABLED").forEach { mode ->
				CapabilityActionButton(mode, item.exposureMode == mode, canEdit) {
					onSaveCapabilitySettings(item.capabilityId, null, mode)
				}
			}
		}
	}
}

@Composable
private fun CapabilityActionButton(
	label: String,
	selected: Boolean,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	if (selected) {
		Button(enabled = enabled, onClick = onClick) { Text(label) }
	} else {
		OutlinedButton(enabled = enabled, onClick = onClick) { Text(label) }
	}
}
