package com.wzx.babiq.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
 * Figma 能力中心表格行模型。
 */
data class CapabilityCenterRowModel(
	val capabilityId: String,
	val displayName: String,
	val source: String,
	val exposureMode: String,
	val enabled: Boolean,
	val lastSeenAt: String,
)

/**
 * 能力详情审计模型。
 */
data class CapabilityCenterDetailModel(
	val capabilityId: String,
	val auditText: String,
)

/**
 * 能力中心页面模型。
 */
data class CapabilityCenterModel(
	val headers: List<String>,
	val rows: List<CapabilityCenterRowModel>,
	val detail: CapabilityCenterDetailModel?,
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
 * 构造 Figma 能力中心表格与详情审计模型。
 */
fun buildCapabilityCenterModel(capabilities: List<CapabilityInfo>): CapabilityCenterModel {
	val rows = capabilities.map { capability ->
		CapabilityCenterRowModel(
			capabilityId = capability.capabilityId,
			displayName = capability.displayName,
			source = "${capability.type} · ${capability.namespace}",
			exposureMode = if (capability.enabled) capability.exposureMode else "DISABLED",
			enabled = capability.enabled,
			lastSeenAt = capability.lastSeenAt ?: "未记录",
		)
	}
	val first = capabilities.firstOrNull()
	return CapabilityCenterModel(
		headers = listOf("能力", "来源", "暴露模式", "最近命中"),
		rows = rows,
		detail = first?.let {
			CapabilityCenterDetailModel(
				capabilityId = it.capabilityId,
				auditText = "来源 ${it.namespace}，协议名 ${it.name}，当前暴露模式 ${it.exposureMode}，执行仍经过 ToolRegistry、审批、沙箱和 SQLite 审计。",
			)
		},
	)
}

/**
 * 中文能力搜索示例。
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
	val model = buildCapabilityCenterModel(visibleList)

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
		CapabilityTable(model, state.canEditSettings, onSaveCapabilitySettings)
		model.detail?.let { detail ->
			CapabilityAuditBlock(detail)
		}
		CapabilityGroupSummary("Local 工具", groups.local)
		CapabilityGroupSummary("MCP 工具", groups.mcp)
		CapabilityGroupSummary("Skill", groups.skills)
	}
}

@Composable
private fun CapabilityTable(
	model: CapabilityCenterModel,
	canEdit: Boolean,
	onSaveCapabilitySettings: (String, Boolean?, String?) -> Unit,
) {
	if (model.rows.isEmpty()) {
		Text("当前没有能力数据。", color = BaBiQColors.Muted)
		return
	}
	Row(modifier = Modifier.fillMaxWidth().background(BaBiQColors.Background, RoundedCornerShape(6.dp)).padding(8.dp)) {
		model.headers.forEachIndexed { index, header ->
			val weight = if (index == 0) 1.4f else 1f
			Text(header, modifier = Modifier.weight(weight), fontWeight = FontWeight.Bold)
		}
		Text("操作", modifier = Modifier.weight(1.6f), fontWeight = FontWeight.Bold)
	}
	model.rows.take(12).forEach { row ->
		Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(row.displayName, modifier = Modifier.weight(1.4f), fontWeight = FontWeight.Medium)
			Text(row.source, modifier = Modifier.weight(1f), color = BaBiQColors.Muted, style = MaterialTheme.typography.labelSmall)
			Text(row.exposureMode, modifier = Modifier.weight(1f), color = BaBiQColors.Muted)
			Text(row.lastSeenAt, modifier = Modifier.weight(1f), color = BaBiQColors.Muted, style = MaterialTheme.typography.labelSmall)
			FlowRow(modifier = Modifier.weight(1.6f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				BooleanSettingButton("启用", row.enabled, canEdit) {
					onSaveCapabilitySettings(row.capabilityId, !row.enabled, null)
				}
				listOf("VISIBLE", "DEFERRED", "DISABLED").forEach { mode ->
					ExposureModeButton(mode, row.exposureMode, canEdit) {
						onSaveCapabilitySettings(row.capabilityId, null, mode)
					}
				}
			}
		}
		HorizontalDivider(color = BaBiQColors.Border)
	}
}

@Composable
private fun CapabilityAuditBlock(detail: CapabilityCenterDetailModel) {
	Column(
		modifier = Modifier.fillMaxWidth().background(BaBiQColors.Background, RoundedCornerShape(8.dp)).padding(12.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Text("详情审计", fontWeight = FontWeight.Bold)
		Text(detail.capabilityId, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
		Text(detail.auditText, color = BaBiQColors.Muted)
	}
}

@Composable
private fun CapabilityGroupSummary(
	title: String,
	items: List<CapabilityInfo>,
) {
	if (items.isEmpty()) {
		return
	}
	Text("$title · ${items.size}", fontWeight = FontWeight.Bold)
	Text(items.take(5).joinToString("、") { it.displayName }, color = BaBiQColors.Muted)
}

@Composable
private fun ExposureModeButton(
	mode: String,
	current: String,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	if (mode == current) {
		Button(enabled = enabled, onClick = onClick) { Text(mode) }
	} else {
		OutlinedButton(enabled = enabled, onClick = onClick) { Text(mode) }
	}
}
