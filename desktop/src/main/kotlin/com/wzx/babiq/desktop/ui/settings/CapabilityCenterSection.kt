package com.wzx.babiq.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 能力中心按来源类型分组后的模型。
 *
 * local、mcp、skills 三类会在 UI 中分开展示，避免用户把本地工具、外部 MCP 和 Skill 正文读取混在一起理解。
 */
data class CapabilityCenterGroups(
	val local: List<CapabilityInfo>,
	val mcp: List<CapabilityInfo>,
	val skills: List<CapabilityInfo>,
)

/**
 * 根据能力类型分组。
 */
fun groupCapabilitiesForCenter(capabilities: List<CapabilityInfo>): CapabilityCenterGroups =
	CapabilityCenterGroups(
		local = capabilities.filter { it.type == "LOCAL_TOOL" },
		mcp = capabilities.filter { it.type == "MCP_TOOL" },
		skills = capabilities.filter { it.type == "SKILL" },
	)

/**
 * 中文能力搜索示例。
 *
 * 这些 query 覆盖 P3-5a 中文别名字典的核心路径，用于让用户快速验证 Lucene/BM25 能否命中本地工具。
 */
fun capabilityExampleQueries(): List<String> =
	listOf("读取文件", "运行命令", "列出目录", "搜索关键字", "打补丁")

/**
 * P3 能力中心。
 *
 * 切换 VISIBLE/DEFERRED/DISABLED 会调用 `capability/settings/set`，下一轮 Agent 构建工具列表时才生效；
 * 当前正在运行的 turn 不会被桌面端中途改写。
 */
@Composable
fun CapabilityCenterSection(
	state: AppState,
	onSaveCapabilitySettings: (String, Boolean?, String?) -> Unit,
	onSearchCapabilities: (String) -> Unit,
) {
	val capability = state.capabilityState
	val status = capability.status
	var query by remember { mutableStateOf("") }
	val visibleList = if (capability.searchResults.isNotEmpty()) capability.searchResults else status?.capabilities.orEmpty()
	val groups = groupCapabilitiesForCenter(visibleList)

	SettingsSectionCard("能力中心") {
		capability.notice?.let { Text(it, color = BaBiQColors.Success) }
		capability.error?.let { Text("能力错误: $it", color = BaBiQColors.Danger) }
		Text(
			"总计 ${status?.totalCount ?: 0} · 已启用 ${status?.enabledCount ?: 0} · 常驻 ${status?.visibleCount ?: 0} · 按需 ${status?.deferredCount ?: 0} · 禁用 ${status?.disabledCount ?: 0}",
			color = BaBiQColors.Muted,
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
			OutlinedTextField(
				value = query,
				onValueChange = { query = it },
				modifier = Modifier.weight(1f),
				singleLine = true,
				label = { Text("中文搜索能力") },
			)
			OutlinedButton(enabled = query.isNotBlank() && !capability.loading, onClick = { onSearchCapabilities(query) }) {
				Text("搜索")
			}
		}
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			capabilityExampleQueries().forEach { example ->
				OutlinedButton(
					enabled = !capability.loading,
					onClick = {
						query = example
						onSearchCapabilities(example)
					},
				) {
					Text(example)
				}
			}
		}
		CapabilityGroup("Local 工具", groups.local, state.canEditSettings, onSaveCapabilitySettings)
		CapabilityGroup("MCP 工具", groups.mcp, state.canEditSettings, onSaveCapabilitySettings)
		CapabilityGroup("Skill", groups.skills, state.canEditSettings, onSaveCapabilitySettings)
	}
}

@Composable
private fun CapabilityGroup(
	title: String,
	items: List<CapabilityInfo>,
	canEdit: Boolean,
	onSaveCapabilitySettings: (String, Boolean?, String?) -> Unit,
) {
	if (items.isEmpty()) {
		return
	}
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text(title, fontWeight = FontWeight.Bold)
		items.take(8).forEach { item ->
			CapabilityCenterRow(item, canEdit, onSaveCapabilitySettings)
		}
	}
}

@Composable
private fun CapabilityCenterRow(
	item: CapabilityInfo,
	canEdit: Boolean,
	onSaveCapabilitySettings: (String, Boolean?, String?) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
		Text(item.displayName, fontWeight = FontWeight.Medium)
		Text(
			"${item.capabilityId} · ${item.type} · ${item.exposureMode}",
			style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
			color = BaBiQColors.Muted,
		)
		Text(item.description.take(140), color = BaBiQColors.Muted)
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			BooleanSettingButton("启用", item.enabled, canEdit) {
				onSaveCapabilitySettings(item.capabilityId, !item.enabled, null)
			}
			listOf("VISIBLE", "DEFERRED", "DISABLED").forEach { mode ->
				ExposureModeButton(mode, item.exposureMode, canEdit) {
					onSaveCapabilitySettings(item.capabilityId, null, mode)
				}
			}
		}
	}
}

@Composable
private fun ExposureModeButton(
	mode: String,
	current: String,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	if (mode == current) {
		androidx.compose.material3.Button(enabled = enabled, onClick = onClick) { Text(mode) }
	} else {
		OutlinedButton(enabled = enabled, onClick = onClick) { Text(mode) }
	}
}
