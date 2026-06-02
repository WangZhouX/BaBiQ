package com.wzx.babiq.desktop.ui.skills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.CapabilityInfo
import com.wzx.babiq.desktop.protocol.SkillInfo
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors
import java.util.Locale

/**
 * Figma 技能页的顶部标签。
 *
 * “插件”保留为父级概念，“技能”是当前选中的真实产品页；00 交互总览只是原型索引，不进入桌面端路由。
 */
data class SkillLibraryTabModel(
	val label: String,
	val selected: Boolean,
)

/** 技能页底部统计 chip。 */
data class SkillLibraryChipModel(
	val label: String,
)

/**
 * 技能列表里的单个卡片模型。
 *
 * @property capabilityId 匹配到的能力 id；为空表示当前 Skill metadata 尚未同步进能力目录。
 */
data class SkillCardModel(
	val skill: SkillInfo,
	val title: String,
	val description: String,
	val sourceLabel: String,
	val exposureLabel: String,
	val enabled: Boolean,
	val capabilityId: String?,
)

/** 技能分区模型。 */
data class SkillLibrarySectionModel(
	val title: String,
	val skills: List<SkillCardModel>,
	val emptyLabel: String = "找不到技能",
)

/** 右侧技能详情抽屉模型。 */
data class SkillDetailModel(
	val skillId: String,
	val title: String,
	val namespace: String,
	val description: String,
	val sourceDirectory: String,
	val skillFile: String,
	val badges: List<String>,
	val primaryAction: String,
	val secondaryAction: String,
	val injectionText: String,
	val contentPreview: String?,
	val capabilityId: String?,
	val enabled: Boolean,
)

/** 技能页整体渲染模型。 */
data class SkillLibraryModel(
	val title: String,
	val tabs: List<SkillLibraryTabModel>,
	val searchPlaceholder: String,
	val filterLabel: String,
	val headerActions: List<String>,
	val chips: List<SkillLibraryChipModel>,
	val sections: List<SkillLibrarySectionModel>,
	val filteredEmptyLabel: String?,
	val detail: SkillDetailModel?,
)

private enum class SkillScope {
	System,
	Project,
	Personal,
}

/**
 * 构造 Figma 技能页模型。
 *
 * 这里不读取 SKILL.md 正文；正文只来自 ChatController.openSkill 写入的 selectedContent。
 */
fun buildSkillLibraryModel(
	state: AppState,
	query: String = "",
	selectedSkillId: String? = state.skillState.selectedSkillId,
): SkillLibraryModel {
	val capabilities = state.capabilityState.status?.capabilities.orEmpty()
	val cards = state.skillState.skills.map { skill ->
		val capability = capabilityForSkill(skill, capabilities)
		SkillCardModel(
			skill = skill,
			title = capability?.displayName?.takeIf { it.isNotBlank() } ?: skill.name,
			description = skill.description.ifBlank { "暂无描述" },
			sourceLabel = sourceLabel(scopeOf(skill, state.workspace.cwd)),
			exposureLabel = exposureLabel(capability),
			enabled = capability?.enabled ?: true,
			capabilityId = capability?.capabilityId,
		)
	}
	val filteredCards = cards.filter { card -> card.matches(query) }
	val selectedSkill = selectedSkillId
		?.let { id -> state.skillState.selectedSkill?.takeIf { it.id == id } ?: cards.firstOrNull { it.skill.id == id }?.skill }
	val selectedCapability = selectedSkill?.let { capabilityForSkill(it, capabilities) }
	val contentLoaded = selectedSkill != null &&
		selectedSkill.id == state.skillState.selectedSkillId &&
		state.skillState.selectedContent != null
	val detail = selectedSkill?.let { skill ->
		val title = selectedCapability?.displayName?.takeIf { it.isNotBlank() } ?: skill.name
		SkillDetailModel(
			skillId = skill.id,
			title = title,
			namespace = skill.namespace,
			description = skill.description.ifBlank { "暂无描述" },
			sourceDirectory = skill.sourceDirectory,
			skillFile = skill.skillFile,
			badges = detailBadges(contentLoaded, state.skillState.selectedContentTruncated),
			primaryAction = if (contentLoaded) "刷新正文" else "查看正文",
			secondaryAction = if (contentLoaded) "复制路径" else "打开目录",
			injectionText = "Skill 正文只在用户显式请求、能力搜索命中或 Agent 需要时按需注入。",
			contentPreview = state.skillState.selectedContent?.takeIf { contentLoaded },
			capabilityId = selectedCapability?.capabilityId,
			enabled = selectedCapability?.enabled ?: true,
		)
	}
	return SkillLibraryModel(
		title = "让 BaBiQ 按你的方式工作",
		tabs = listOf(
			SkillLibraryTabModel("插件", selected = false),
			SkillLibraryTabModel("技能", selected = true),
		),
		searchPlaceholder = "搜索技能",
		filterLabel = "全部",
		headerActions = listOf("管理", "创建", "更多"),
		chips = listOf(
			SkillLibraryChipModel("本地 ${cards.size}"),
			SkillLibraryChipModel("系统 ${cards.count { scopeOf(it.skill, state.workspace.cwd) == SkillScope.System }}"),
			SkillLibraryChipModel("个人 ${cards.count { scopeOf(it.skill, state.workspace.cwd) == SkillScope.Personal }}"),
			SkillLibraryChipModel("项目 ${cards.count { scopeOf(it.skill, state.workspace.cwd) == SkillScope.Project }}"),
		),
		sections = listOf(
			SkillLibrarySectionModel("推荐", emptyList()),
			SkillLibrarySectionModel("系统", filteredCards.filter { scopeOf(it.skill, state.workspace.cwd) == SkillScope.System }),
			SkillLibrarySectionModel("项目", filteredCards.filter { scopeOf(it.skill, state.workspace.cwd) == SkillScope.Project }),
			SkillLibrarySectionModel("个人", filteredCards.filter { scopeOf(it.skill, state.workspace.cwd) == SkillScope.Personal }),
		),
		filteredEmptyLabel = if (filteredCards.isEmpty()) "找不到技能" else null,
		detail = detail,
	)
}

/**
 * Figma P3-6 技能页。
 *
 * 页面只接入已有 `skills/list`、`skills/get` 和 `capability/settings/set` 协议；
 * 管理弹窗展示当前发现的目录，不在这里新增任意本地路径写入能力。
 */
@Composable
fun SkillLibraryPanel(
	state: AppState,
	onOpenSkill: (String) -> Unit,
	onSaveCapabilitySettings: (String, Boolean?, String?) -> Unit,
) {
	var query by remember { mutableStateOf("") }
	var selectedSkillId by remember(state.skillState.selectedSkillId) { mutableStateOf(state.skillState.selectedSkillId) }
	var showManager by remember { mutableStateOf(false) }
	val model = buildSkillLibraryModel(state, query, selectedSkillId)
	val clipboard = LocalClipboardManager.current

	Row(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 34.dp, vertical = 28.dp),
		horizontalArrangement = Arrangement.spacedBy(18.dp),
	) {
		Column(
			modifier = Modifier
				.weight(1f)
				.fillMaxHeight()
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(18.dp),
		) {
			SkillLibraryHeader(
				model = model,
				query = query,
				onQueryChange = { query = it },
				onManage = { showManager = true },
			)
			if (state.skillState.loading) {
				Text("正在加载技能目录...", color = BaBiQColors.Muted)
			}
			state.skillState.error?.let { Text("Skill 错误: $it", color = BaBiQColors.Danger) }
			model.filteredEmptyLabel?.let { Text(it, color = BaBiQColors.Muted, modifier = Modifier.padding(vertical = 28.dp)) }
			model.sections.forEach { section ->
				SkillSection(
					section = section,
					selectedSkillId = selectedSkillId,
					onSelect = { selectedSkillId = it },
				)
			}
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				model.chips.forEach { chip -> SkillChip(chip.label) }
			}
		}
		model.detail?.let { detail ->
			SkillDetailDrawer(
				detail = detail,
				loading = state.skillState.contentLoading,
				onPrimaryAction = { onOpenSkill(detail.skillId) },
				onSecondaryAction = {
					if (detail.contentPreview != null) {
						clipboard.setText(AnnotatedString(detail.skillFile))
					}
				},
				onToggleEnabled = {
					detail.capabilityId?.let { capabilityId ->
						onSaveCapabilitySettings(capabilityId, !detail.enabled, null)
					}
				},
			)
		}
	}
	if (showManager) {
		SkillDirectoryManagerDialog(
			skills = state.skillState.skills,
			workspaceCwd = state.workspace.cwd,
			onDismiss = { showManager = false },
		)
	}
}

@Composable
private fun SkillLibraryHeader(
	model: SkillLibraryModel,
	query: String,
	onQueryChange: (String) -> Unit,
	onManage: () -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
				model.tabs.forEach { tab ->
					Text(
						tab.label,
						style = MaterialTheme.typography.titleMedium.copy(
							fontWeight = if (tab.selected) FontWeight.Bold else FontWeight.Normal,
						),
						color = if (tab.selected) BaBiQColors.Ink else BaBiQColors.Muted,
					)
				}
			}
			Spacer(Modifier.weight(1f))
			OutlinedButton(onClick = onManage) { Text("管理") }
			OutlinedButton(onClick = {}) { Text("创建 ▾") }
			TextButton(onClick = {}) { Text("⋯") }
		}
		Text(
			model.title,
			style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium),
			modifier = Modifier.align(Alignment.CenterHorizontally),
		)
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
			OutlinedTextField(
				value = query,
				onValueChange = onQueryChange,
				placeholder = { Text(model.searchPlaceholder) },
				modifier = Modifier.weight(1f),
				singleLine = true,
				leadingIcon = { Text("⌕") },
			)
			OutlinedButton(onClick = {}) { Text("${model.filterLabel} ▾") }
		}
	}
}

@Composable
private fun SkillSection(
	section: SkillLibrarySectionModel,
	selectedSkillId: String?,
	onSelect: (String) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Text(section.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
		HorizontalDivider(color = BaBiQColors.Border)
		if (section.skills.isEmpty()) {
			Text(
				section.emptyLabel,
				color = BaBiQColors.Muted,
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 28.dp),
			)
		} else {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				section.skills.chunked(2).forEach { row ->
					Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
						row.forEach { card ->
							SkillCard(
								card = card,
								selected = card.skill.id == selectedSkillId,
								onClick = { onSelect(card.skill.id) },
								modifier = Modifier.weight(1f),
							)
						}
						if (row.size == 1) {
							Spacer(Modifier.weight(1f))
						}
					}
				}
			}
		}
	}
}

@Composable
private fun SkillCard(
	card: SkillCardModel,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.clip(RoundedCornerShape(8.dp))
			.background(if (selected) Color(0xFFEAF0FA) else Color.Transparent)
			.clickable(onClick = onClick)
			.padding(10.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		SkillIcon(card.title)
		Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(card.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1)
			Text(card.description, color = BaBiQColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text("${card.sourceLabel} · ${card.exposureLabel}", color = BaBiQColors.Muted, style = MaterialTheme.typography.labelSmall)
		}
		Text(if (card.enabled) "✓" else "○", color = if (card.enabled) BaBiQColors.Muted else BaBiQColors.Warning)
	}
}

@Composable
private fun SkillIcon(title: String) {
	Box(
		modifier = Modifier
			.size(40.dp)
			.background(iconColor(title), RoundedCornerShape(8.dp)),
		contentAlignment = Alignment.Center,
	) {
		Text(title.firstOrNull()?.uppercaseChar()?.toString() ?: "S", color = Color.White, fontWeight = FontWeight.Bold)
	}
}

@Composable
private fun SkillDetailDrawer(
	detail: SkillDetailModel,
	loading: Boolean,
	onPrimaryAction: () -> Unit,
	onSecondaryAction: () -> Unit,
	onToggleEnabled: () -> Unit,
) {
	Card(
		modifier = Modifier
			.width(360.dp)
			.fillMaxHeight(),
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Panel),
	) {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(16.dp)
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(detail.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
			Text("${detail.namespace} · ${detail.skillId}", color = BaBiQColors.Muted, style = MaterialTheme.typography.labelSmall)
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				detail.badges.forEach { badge -> SkillChip(badge) }
			}
			Text(detail.description)
			Text(detail.injectionText, color = BaBiQColors.Muted, style = MaterialTheme.typography.bodySmall)
			Text(detail.skillFile, color = BaBiQColors.Muted, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Button(enabled = !loading, onClick = onPrimaryAction) {
					Text(if (loading) "读取中..." else detail.primaryAction)
				}
				OutlinedButton(onClick = onSecondaryAction) {
					Text(detail.secondaryAction)
				}
				detail.capabilityId?.let {
					OutlinedButton(onClick = onToggleEnabled) {
						Text(if (detail.enabled) "停用" else "启用")
					}
				}
			}
			detail.contentPreview?.let { content ->
				SelectionContainer {
					Text(
						content,
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(max = 520.dp)
							.background(BaBiQColors.Background, RoundedCornerShape(8.dp))
							.padding(12.dp),
						style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
					)
				}
			}
		}
	}
}

@Composable
private fun SkillDirectoryManagerDialog(
	skills: List<SkillInfo>,
	workspaceCwd: String,
	onDismiss: () -> Unit,
) {
	val grouped = skills.groupBy { sourceLabel(scopeOf(it, workspaceCwd)) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("技能目录管理") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text("当前只展示后端已发现的 Skill 目录；新增目录仍由后端配置和后续安装流程接入。", color = BaBiQColors.Muted)
				grouped.forEach { (label, items) ->
					Text(label, fontWeight = FontWeight.Bold)
					items.map { it.sourceDirectory }.distinct().take(6).forEach { dir ->
						Text(dir, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
					}
				}
				if (skills.isEmpty()) {
					Text("暂未发现 Skill 目录。", color = BaBiQColors.Muted)
				}
			}
		},
		confirmButton = {
			Button(onClick = onDismiss) { Text("保存") }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("取消") }
		},
	)
}

@Composable
private fun SkillChip(label: String) {
	Text(
		text = label,
		color = BaBiQColors.Muted,
		style = MaterialTheme.typography.labelSmall,
		modifier = Modifier
			.background(Color(0xFFE8EEF8), RoundedCornerShape(8.dp))
			.padding(horizontal = 9.dp, vertical = 5.dp),
	)
}

private fun SkillCardModel.matches(query: String): Boolean {
	val normalized = query.trim().lowercase(Locale.ROOT)
	if (normalized.isEmpty()) {
		return true
	}
	return listOf(
		title,
		description,
		skill.id,
		skill.namespace,
		skill.name,
		skill.sourceDirectory,
		skill.allowedTools.joinToString(" "),
	).any { it.lowercase(Locale.ROOT).contains(normalized) }
}

private fun capabilityForSkill(skill: SkillInfo, capabilities: List<CapabilityInfo>): CapabilityInfo? =
	capabilities.firstOrNull { capability ->
		capability.type == "SKILL" &&
			(capability.capabilityId == skill.id ||
				capability.name.equals(skill.name, ignoreCase = true) ||
				capability.capabilityId.endsWith(".${skill.name}", ignoreCase = true))
	}

private fun scopeOf(skill: SkillInfo, workspaceCwd: String): SkillScope {
	val namespace = skill.namespace.lowercase(Locale.ROOT)
	val source = skill.sourceDirectory.replace('\\', '/').lowercase(Locale.ROOT)
	val workspace = workspaceCwd.replace('\\', '/').lowercase(Locale.ROOT).trimEnd('/')
	return when {
		namespace.contains("system") || source.contains("/.system/") -> SkillScope.System
		namespace.contains("project") || (workspace.isNotBlank() && source.startsWith(workspace)) -> SkillScope.Project
		else -> SkillScope.Personal
	}
}

private fun sourceLabel(scope: SkillScope): String =
	when (scope) {
		SkillScope.System -> "系统"
		SkillScope.Project -> "项目"
		SkillScope.Personal -> "个人"
	}

private fun exposureLabel(capability: CapabilityInfo?): String =
	when (capability?.exposureMode) {
		"VISIBLE" -> "常驻"
		"DEFERRED" -> "按需注入"
		"DISABLED" -> "已停用"
		null -> "未同步"
		else -> capability.exposureMode
	}

private fun detailBadges(contentLoaded: Boolean, truncated: Boolean): List<String> =
	if (contentLoaded) {
		buildList {
			add(if (truncated) "已截断" else "正文已加载")
			add("本轮可注入")
		}
	} else {
		listOf("按需注入", "正文未加载", "可执行")
	}

private fun iconColor(title: String): Color =
	when (title.lowercase(Locale.ROOT).hashCode().mod(5)) {
		0 -> Color(0xFF5BC0EB)
		1 -> Color(0xFFFF9F1C)
		2 -> Color(0xFFFF6B6B)
		3 -> Color(0xFF7C6ADE)
		else -> Color(0xFF2F6F4E)
	}
