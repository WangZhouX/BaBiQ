package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.CapabilityStatusResult
import com.wzx.babiq.desktop.protocol.RunApprovalInfo
import com.wzx.babiq.desktop.protocol.RunToolCallInfo
import com.wzx.babiq.desktop.protocol.RunTurnDetailResult
import com.wzx.babiq.desktop.protocol.protocolJson
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.CapabilityUiState
import com.wzx.babiq.desktop.state.MemoryUiState
import com.wzx.babiq.desktop.state.ObservabilityState
import com.wzx.babiq.desktop.state.RunRecordState
import com.wzx.babiq.desktop.state.RunTurnListItem
import com.wzx.babiq.desktop.ui.theme.BaBiQColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 右侧运行详情面板。
 *
 * 它展示和聊天主区同一份状态：当前 turn 状态、最近运行摘要、实时事件，以及 P2-4 持久化后的历史运行记录。
 */
enum class RuntimePanelTab {
	Run,
	Orchestration,
	Team,
	SubAgent,
}

data class RuntimePanelTabItem(
	val tab: RuntimePanelTab,
	val label: String,
	val visible: Boolean,
	val selected: Boolean,
)

enum class RuntimePanelContent {
	Plan,
	WorkUnits,
	Environment,
	Context,
	RunRecords,
	Observability,
	Summary,
	Status,
	Events,
	EmptyState,
	Orchestration,
	Team,
	SubAgent,
}

private sealed interface RuntimeRemovalTarget {
	val title: String
	val message: String

	data class WorkUnit(
		val workUnitId: String,
		val displayName: String,
	) : RuntimeRemovalTarget {
		override val title: String = "确认移除工作容器"
		override val message: String =
			"将从右侧列表移除「$displayName」。这是软移除，SQLite 审计记录、历史运行记录和工具调用记录仍会保留。"
	}

	data class Orchestration(
		val displayName: String,
	) : RuntimeRemovalTarget {
		override val title: String = "确认移除编排卡片"
		override val message: String =
			"将从右侧详情隐藏「$displayName」。这不会删除 WorkUnit、聊天历史或运行审计记录。"
	}

	data class Team(
		val displayName: String,
	) : RuntimeRemovalTarget {
		override val title: String = "确认移除团队卡片"
		override val message: String =
			"将从右侧详情隐藏「$displayName」。这不会删除 WorkUnit、聊天历史或运行审计记录。"
	}
}

fun runtimePanelTabs(state: AppState, selectedTab: RuntimePanelTab): List<RuntimePanelTabItem> {
	val resolved = resolveRuntimePanelTab(state, selectedTab)
	return listOfNotNull(
		RuntimePanelTabItem(RuntimePanelTab.Run, "运行", visible = true, selected = resolved == RuntimePanelTab.Run),
		RuntimePanelTabItem(
			RuntimePanelTab.Orchestration,
			"编排",
			visible = true,
			selected = resolved == RuntimePanelTab.Orchestration,
		),
		RuntimePanelTabItem(
			RuntimePanelTab.SubAgent,
			"子代理",
			visible = state.subAgentState.visible,
			selected = resolved == RuntimePanelTab.SubAgent,
		).takeIf { it.visible },
	)
}

fun resolveRuntimePanelTab(state: AppState, requested: RuntimePanelTab): RuntimePanelTab =
	when (requested) {
		RuntimePanelTab.Orchestration -> RuntimePanelTab.Orchestration
		RuntimePanelTab.Team -> RuntimePanelTab.Run
		RuntimePanelTab.SubAgent -> requested.takeIf { state.subAgentState.visible } ?: RuntimePanelTab.Run
		RuntimePanelTab.Run -> RuntimePanelTab.Run
	}

fun preferredRuntimePanelTab(state: AppState, current: RuntimePanelTab): RuntimePanelTab =
	when {
		state.orchestrationState.configuringWorkUnit != null -> RuntimePanelTab.Orchestration
		else -> resolveRuntimePanelTab(state, current)
	}

fun runtimePanelContent(tab: RuntimePanelTab): Set<RuntimePanelContent> =
	when (tab) {
		RuntimePanelTab.Run -> setOf(
			RuntimePanelContent.Plan,
			RuntimePanelContent.Environment,
			RuntimePanelContent.Context,
			RuntimePanelContent.RunRecords,
			RuntimePanelContent.Observability,
			RuntimePanelContent.Summary,
			RuntimePanelContent.Status,
			RuntimePanelContent.Events,
			RuntimePanelContent.EmptyState,
		)
		RuntimePanelTab.Orchestration -> setOf(RuntimePanelContent.WorkUnits)
		RuntimePanelTab.Team -> emptySet()
		RuntimePanelTab.SubAgent -> setOf(RuntimePanelContent.SubAgent)
	}

fun runtimePanelContent(state: AppState, tab: RuntimePanelTab): Set<RuntimePanelContent> =
	when (tab) {
		RuntimePanelTab.Orchestration ->
			if (state.orchestrationState.configuringWorkUnit != null) {
				setOf(RuntimePanelContent.Orchestration)
			} else {
				runtimePanelContent(tab)
			}
		RuntimePanelTab.Team ->
			if (state.teamState.configuringWorkUnit != null) {
				setOf(RuntimePanelContent.Team)
			} else {
				runtimePanelContent(tab)
			}
		else -> runtimePanelContent(tab)
	}

private fun AppState.workUnitRemovalDisplayName(workUnitId: String): String {
	val detail = workUnitState.details.firstOrNull { it.workUnitId == workUnitId }
	if (detail != null) {
		return "${detail.kind.displayKindLabel()} · ${detail.name}"
	}
	val item = workUnitState.items.firstOrNull { it.workUnitId == workUnitId }
	if (item != null) {
		return "${item.kind.displayKindLabel()} · ${item.name}"
	}
	return workUnitId
}

private fun String.displayKindLabel(): String =
	when (lowercase()) {
		"orchestration" -> "编排"
		"team" -> "团队"
		else -> this
	}

@Composable
fun RuntimeDetailsPanel(
	state: AppState,
	modifier: Modifier = Modifier,
	onClose: () -> Unit,
	onDismissSubAgent: () -> Unit = {},
	onDismissOrchestration: () -> Unit = {},
	onDismissTeam: () -> Unit = {},
	onSelectWorkUnit: (String) -> Unit = {},
	onConfigureWorkUnit: (String) -> Unit = {},
	onBackToWorkUnitList: () -> Unit = {},
	onStartWorkUnit: (String) -> Unit = {},
	onRemoveWorkUnit: (String) -> Unit = {},
	onUpdateWorkUnitGoal: (String, String, String) -> Unit = { _, _, _ -> },
	onRenameWorkUnit: (String, String) -> Unit = { _, _ -> },
	onUpdateWorkUnitConfig: (String, String, String?) -> Unit = { _, _, _ -> },
	onMarkWorkUnitConfigDraftDirty: (String) -> Unit = {},
	onLoadLatestWorkUnitConfig: (String) -> Unit = {},
	onKeepWorkUnitConfigDraft: (String) -> Unit = {},
	onSendTeamMessage: (String, String) -> Unit = { _, _ -> },
	onSelectTeam: (String) -> Unit = {},
	onSelectRunTurn: (String) -> Unit,
	onSelectObservabilityRange: (String) -> Unit,
) {
	var selectedTab by remember { mutableStateOf(RuntimePanelTab.Run) }
	val preferredTab = preferredRuntimePanelTab(state, selectedTab)
	LaunchedEffect(preferredTab) {
		selectedTab = preferredTab
	}
	val effectiveTab = resolveRuntimePanelTab(state, selectedTab)
	val tabs = runtimePanelTabs(state, effectiveTab)
	var pendingRemoval by remember { mutableStateOf<RuntimeRemovalTarget?>(null) }
	pendingRemoval?.let { target ->
		AlertDialog(
			onDismissRequest = { pendingRemoval = null },
			title = { Text(target.title) },
			text = { Text(target.message) },
			confirmButton = {
				TextButton(
					onClick = {
						when (target) {
							is RuntimeRemovalTarget.WorkUnit -> onRemoveWorkUnit(target.workUnitId)
							is RuntimeRemovalTarget.Orchestration -> onDismissOrchestration()
							is RuntimeRemovalTarget.Team -> onDismissTeam()
						}
						pendingRemoval = null
					},
				) {
					Text("确认移除")
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingRemoval = null }) { Text("取消") }
			},
		)
	}
	val requestWorkUnitRemoval: (String) -> Unit = { workUnitId ->
		pendingRemoval = RuntimeRemovalTarget.WorkUnit(
			workUnitId = workUnitId,
			displayName = state.workUnitRemovalDisplayName(workUnitId),
		)
	}
	val requestOrchestrationDismiss: () -> Unit = {
		pendingRemoval = RuntimeRemovalTarget.Orchestration(
			displayName = state.orchestrationState.current?.title?.takeIf { it.isNotBlank() } ?: "当前编排",
		)
	}
	val requestTeamDismiss: () -> Unit = {
		pendingRemoval = RuntimeRemovalTarget.Team(
			displayName = state.teamState.current?.title?.takeIf { it.isNotBlank() } ?: "当前团队",
		)
	}
	Column(
		modifier = modifier
			.fillMaxHeight()
			.background(BaBiQColors.Panel)
			.padding(12.dp),
	) {
		Column(
			modifier = Modifier
				.fillMaxHeight()
				.fillMaxWidth()
				.background(BaBiQColors.Background, RoundedCornerShape(12.dp))
				.border(1.dp, BaBiQColors.Border, RoundedCornerShape(12.dp))
				.padding(14.dp)
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			RuntimePanelHeader(
				selectedTab = effectiveTab,
				tabs = tabs,
				onSelectTab = { selectedTab = it },
				onClose = onClose,
			)
			TeamSection(
				state = state.teamState,
				modelLabel = state.providerState.active.label,
				providerState = state.providerState,
				onStartWorkUnit = onStartWorkUnit,
				onRemoveWorkUnit = requestWorkUnitRemoval,
				onDismissTeam = requestTeamDismiss,
				onBackToList = onBackToWorkUnitList,
				onUpdateWorkUnitGoal = onUpdateWorkUnitGoal,
				onRenameWorkUnit = onRenameWorkUnit,
				onUpdateWorkUnitConfig = { workUnitId, configJson ->
					onUpdateWorkUnitConfig(workUnitId, configJson, null)
				},
				onSendTeamMessage = onSendTeamMessage,
				onSelectTeam = onSelectTeam,
			)
			when (effectiveTab) {
				RuntimePanelTab.Run -> {
					PlanSection(state.planState)
					DetailCard(
						title = "执行环境",
						detail = buildString {
							append("目录: ").append(state.workspace.projectName).append(" / ").append(state.workspace.cwd)
							append("\n权限: ").append(state.workspace.permissionLabel ?: state.workspace.permissionMode ?: "未加载")
							append("\n模型: ").append(state.providerState.active.label)
						},
					)
					DetailCard(
						title = "上下文来源",
						detail = buildString {
							append("窗口: ").append(state.contextWindowState.status?.let { "${(it.usageRatio * 100).toInt()}%" } ?: "未生成")
							append("\n短期压缩: ").append(state.contextWindowState.status?.activeSummaryId ?: "未启用")
							append("\n长期记忆: ").append(if (state.memoryState.status?.readEnabled == true) "注入开启" else "注入关闭")
							append("\n能力装配: ").append(state.capabilityState.status?.summaryText() ?: "未加载")
						},
					)
					RunRecordSection(
						state = state.runRecordState,
						memoryState = state.memoryState,
						capabilityState = state.capabilityState,
						onSelectRunTurn = onSelectRunTurn,
					)
					ObservabilitySection(
						state = state.runRecordState.observability,
						onSelectRange = onSelectObservabilityRange,
					)
					// 运行摘要在这里作为详情复用；聊天流里的 TurnSummaryBar 仍然是主展示位置。
					state.latestSummary?.let { TurnSummaryBar(it) }
					DetailCard("当前状态", "${state.turnState} / ${state.connectionState}")
					state.runtimeEvents.forEach { event ->
						DetailCard(event.title, event.detail + event.raw?.let { "\n$it" }.orEmpty())
					}
					if (state.runtimeEvents.isEmpty() && state.latestSummary == null) {
						Text("暂无运行详情。完成一轮任务后，这里会显示工具轨迹和 token 明细。", color = BaBiQColors.Muted)
					}
				}
				RuntimePanelTab.Orchestration -> {
					val content = runtimePanelContent(state, effectiveTab)
					if (RuntimePanelContent.WorkUnits in content) {
						WorkUnitSection(
							state = state.workUnitState,
							kindFilter = "orchestration",
							onSelect = onSelectWorkUnit,
							onConfigure = onConfigureWorkUnit,
							onRemove = requestWorkUnitRemoval,
							onUpdateGoal = onUpdateWorkUnitGoal,
						)
					}
					if (RuntimePanelContent.Orchestration in content) {
						OrchestrationSection(
							state = state.orchestrationState,
							modelLabel = state.providerState.active.label,
							providerState = state.providerState,
							onStartWorkUnit = onStartWorkUnit,
							onRemoveWorkUnit = requestWorkUnitRemoval,
							onDismissOrchestration = requestOrchestrationDismiss,
							onBackToList = onBackToWorkUnitList,
							onUpdateWorkUnitGoal = onUpdateWorkUnitGoal,
							onRenameWorkUnit = onRenameWorkUnit,
							onUpdateWorkUnitConfig = onUpdateWorkUnitConfig,
							onMarkWorkUnitConfigDraftDirty = onMarkWorkUnitConfigDraftDirty,
							onLoadLatestWorkUnitConfig = onLoadLatestWorkUnitConfig,
							onKeepWorkUnitConfigDraft = onKeepWorkUnitConfigDraft,
						)
					}
				}
				RuntimePanelTab.Team -> Unit
				RuntimePanelTab.SubAgent -> SubAgentSection(state.subAgentState, onDismiss = onDismissSubAgent)
			}
		}
	}
}

/**
 * 本地可观测统计区域。
 *
 * 这里展示的是“当前工作目录”的聚合统计，不是某一条 turn 的详情。
 * range 按钮只刷新 observability/snapshot，不会切换聊天会话或重新发送任务。
 */
@Composable
private fun RuntimePanelHeader(
	selectedTab: RuntimePanelTab,
	tabs: List<RuntimePanelTabItem>,
	onSelectTab: (RuntimePanelTab) -> Unit,
	onClose: () -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
			Text(
				runtimePanelTitle(selectedTab),
				style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
			)
			TextButton(onClick = onClose) { Text("收起") }
		}
		Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
			tabs.forEach { item ->
				if (item.selected) {
					OutlinedButton(onClick = { onSelectTab(item.tab) }, enabled = false) {
						Text(item.label)
					}
				} else {
					TextButton(onClick = { onSelectTab(item.tab) }) {
						Text(item.label)
					}
				}
			}
		}
	}
}

private fun runtimePanelTitle(tab: RuntimePanelTab): String =
	when (tab) {
		RuntimePanelTab.Run -> "运行详情"
		RuntimePanelTab.Orchestration -> "编排详情"
		RuntimePanelTab.Team -> "团队详情"
		RuntimePanelTab.SubAgent -> "子代理详情"
	}

@Composable
private fun ObservabilitySection(
	state: ObservabilityState,
	onSelectRange: (String) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
			Text("本地统计", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
			Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				RangeButton("7d", "7天", state.range, onSelectRange)
				RangeButton("30d", "30天", state.range, onSelectRange)
				RangeButton("all", "全部", state.range, onSelectRange)
			}
		}
		if (state.loading) {
			Text("正在读取本地统计...", color = BaBiQColors.Muted)
		}
		state.error?.let { DetailCard("统计错误", it) }
		state.snapshot?.let { snapshot ->
			val totals = snapshot.totals
			// 总 token 由后端从数据库聚合后返回，桌面端只负责展示同一份事实数据。
			DetailCard(
				title = "统计总览",
				detail = buildString {
					append("turn: ").append(totals.turns)
					append("\n失败: ").append(totals.failedTurns)
					append("\n输入 token: ").append(totals.promptTokens)
					append("\n输出 token: ").append(totals.completionTokens)
					append("\n总 token: ").append(totals.totalTokens)
				},
			)
			if (snapshot.byModel.isNotEmpty()) {
				DetailCard(
					title = "模型用量",
					detail = snapshot.byModel.take(3).joinToString("\n") { model ->
						// 模型维度同样只展示 token，用于排查哪类模型消耗最多上下文。
						"${model.model ?: model.providerId ?: "未知模型"} / ${model.turns} turn / ${model.totalTokens} token"
					},
				)
			}
			if (snapshot.byTool.isNotEmpty()) {
				DetailCard(
					title = "工具使用",
					detail = snapshot.byTool.take(5).joinToString("\n") { tool ->
						"${tool.toolName} / ${tool.calls} 次 / 失败 ${tool.failures} / 平均 ${tool.avgDurationMs} ms"
					},
				)
			}
		}
	}
}

/**
 * 统计窗口切换按钮。
 */
@Composable
private fun RangeButton(
	range: String,
	label: String,
	currentRange: String,
	onSelectRange: (String) -> Unit,
) {
	TextButton(onClick = { onSelectRange(range) }) {
		Text(if (range == currentRange) "[$label]" else label)
	}
}

/**
 * 持久化运行记录区域。
 *
 * 这个区域只消费 RunRecordState，不直接发网络请求；点击历史 turn 时把意图交回 Controller。
 */
@Composable
private fun RunRecordSection(
	state: RunRecordState,
	memoryState: MemoryUiState,
	capabilityState: CapabilityUiState,
	onSelectRunTurn: (String) -> Unit,
) {
	state.recoveryStatus?.let { recovery ->
		DetailCard(
			title = "启动恢复",
			detail = buildString {
				append("最近恢复: ").append(recovery.lastRecoveredAt ?: "暂无")
				append("\n中断 turn: ").append(recovery.interruptedTurns)
				append("\n过期 turn: ").append(recovery.expiredTurns)
				append("\n过期审批: ").append(recovery.expiredApprovals)
			},
		)
	}
	if (state.loading) {
		Text("正在读取运行记录...", color = BaBiQColors.Muted)
	}
	state.error?.let { DetailCard("运行记录错误", it) }
	if (state.turns.isNotEmpty()) {
		Text("最近运行", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
		Text("当前会话的最近运行记录，不是历史对话。点击后在下方展开详情。", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		val visibleTurns = state.turns.take(4)
		var selectedRendered = false
		visibleTurns.forEach { turn ->
			RunTurnRow(
				turn = turn,
				selected = turn.turnId == state.selectedTurnId,
				action = state.actionForTurn(turn.turnId),
				onSelectRunTurn = onSelectRunTurn,
			)
			if (turn.turnId == state.selectedTurnId) {
				val detail = state.detailForTurn(turn.turnId)
				if (detail != null) {
					selectedRendered = true
					RunTurnDetail(
						detail = detail,
						memoryState = memoryState,
						capabilityState = capabilityState,
					)
				} else if (state.isDetailLoadingForTurn(turn.turnId)) {
					selectedRendered = true
					DetailCard("本轮详情", "正在读取本轮详情...")
				}
			}
		}
		if (state.turns.size > visibleTurns.size) {
			Text("仅显示最近 ${visibleTurns.size} 轮，完整记录仍保留在本地数据库。", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		}
		if (!selectedRendered) {
			val selectedTurnId = state.selectedTurnId
			val detail = selectedTurnId?.let { state.detailForTurn(it) }
			if (detail != null) {
				RunTurnDetail(
					detail = detail,
					memoryState = memoryState,
					capabilityState = capabilityState,
				)
			} else if (selectedTurnId != null && state.isDetailLoadingForTurn(selectedTurnId)) {
				DetailCard("本轮详情", "正在读取本轮详情...")
			}
		}
	}
}

internal fun RunRecordState.detailForTurn(turnId: String): RunTurnDetailResult? =
	selectedDetail?.takeIf { selectedTurnId == turnId && it.turn.turnId == turnId }

internal fun RunRecordState.isDetailLoadingForTurn(turnId: String): Boolean =
	selectedTurnId == turnId && (loading || selectedDetail?.turn?.turnId != turnId)

internal enum class RunTurnAction {
	View,
	ReadLoading,
}

internal fun RunRecordState.actionForTurn(turnId: String): RunTurnAction? =
	when {
		selectedTurnId != turnId -> RunTurnAction.View
		isDetailLoadingForTurn(turnId) -> RunTurnAction.ReadLoading
		detailForTurn(turnId) != null -> null
		else -> RunTurnAction.View
	}

/**
 * 历史 turn 列表项。
 */
@Composable
private fun RunTurnRow(
	turn: RunTurnListItem,
	selected: Boolean,
	action: RunTurnAction?,
	onSelectRunTurn: (String) -> Unit,
) {
	Card(
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, if (selected) BaBiQColors.Accent else BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Background),
	) {
		Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
				Text(turn.statusLabel, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
				when (action) {
					RunTurnAction.View -> TextButton(onClick = { onSelectRunTurn(turn.turnId) }) { Text("查看") }
					RunTurnAction.ReadLoading -> Text("读取中", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
					null -> Unit
				}
			}
			Text(turn.inputPreview, style = MaterialTheme.typography.bodySmall)
			Text("${turn.modelLabel} / ${turn.timeLabel}", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
			turn.recoveryReason?.let {
				Text("恢复原因: $it", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
			}
		}
	}
}

/**
 * 选中历史 turn 的详情。
 */
@Composable
private fun RunTurnDetail(
	detail: RunTurnDetailResult,
	@Suppress("UNUSED_PARAMETER") memoryState: MemoryUiState,
	@Suppress("UNUSED_PARAMETER") capabilityState: CapabilityUiState,
) {
	DetailCard(
		title = "本轮详情",
		detail = buildString {
			append("状态: ").append(runStatusLabel(detail.turn.status))
			append("\n输入: ").append(detail.turn.inputText.ifBlank { "空输入" })
			detail.turn.recoveryReason?.let { append("\n恢复原因: ").append(it) }
		},
	)
	detail.summary?.let { TurnSummaryBar(it) }
	detail.contextSnapshot?.let { snapshot -> ContextSnapshotSection(snapshot) }
	if (detail.toolCalls.isNotEmpty()) {
		ToolCallSummarySection(detail.toolCalls)
	}
	if (detail.approvals.isNotEmpty()) {
		DetailCard("审批记录", detail.approvals.joinToString("\n") { it.approvalLine() })
	}
}

/**
 * 将工具调用详情压成一行，避免右侧面板被长 JSON 输出撑开。
 */
@Composable
private fun ToolCallSummarySection(toolCalls: List<RunToolCallInfo>) {
	AuditSectionCard("工具调用摘要") {
		Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			toolCalls.take(8).forEach { call ->
				Text(call.readableToolCallLine(), style = MaterialTheme.typography.bodySmall)
			}
			if (toolCalls.size > 8) {
				Text("还有 ${toolCalls.size - 8} 次工具调用未展开。", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
			}
		}
	}
}

internal fun RunToolCallInfo.readableToolCallLine(): String {
	val agentPrefix = agentName
		?.takeIf { it.isNotBlank() }
		?.let { agent -> "[$agent] " }
		.orEmpty()
	val preview = cleanToolPreview(errorMessage ?: resultPreview ?: "无结果预览")
	return "$agentPrefix${toolDisplayName(toolName)} · ${toolStatusLabel(status)} · ${preview.truncateMiddle(140)}"
}

private val untrustedDataBlock = Regex("""(?s)<untrusted-data\b[^>]*>(.*?)</untrusted-data>""")

private fun cleanToolPreview(preview: String): String {
	val unwrapped = untrustedDataBlock.replace(preview) { it.groupValues[1] }.trim()
	if (unwrapped.isBlank()) {
		return "无结果预览"
	}
	return renderJsonPreview(unwrapped) ?: unwrapped
}

private fun renderJsonPreview(text: String): String? {
	val element = runCatching { protocolJson.parseToJsonElement(text) }.getOrNull() ?: return null
	return when (element) {
		is JsonObject -> element.stringValue("error") ?: element.stringValue("output") ?: element.toString()
		is JsonArray -> element.joinToString("，") { renderJsonPreview(it.toString()) ?: it.toString().trim('"') }
		is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() } ?: text
	}
}

private fun JsonObject.stringValue(name: String): String? =
	this[name]?.let { value ->
		when (value) {
			is JsonPrimitive -> value.contentOrNull
			else -> value.toString()
		}
	}?.trim()?.takeIf { it.isNotBlank() }

private fun toolDisplayName(toolName: String): String =
	when (toolName.lowercase()) {
		"orchestrate_flow" -> "编排执行"
		"coordinate_team" -> "团队协作"
		"work_unit_manage" -> "工作器管理"
		"read_file" -> "读取文件"
		"list_dir" -> "列出目录"
		"write_file" -> "写入文件"
		"edit_file" -> "编辑文件"
		"exec_shell" -> "执行命令"
		"grep" -> "搜索内容"
		else -> toolName
	}

private fun toolStatusLabel(status: String): String =
	when (status.lowercase()) {
		"completed", "success", "succeeded" -> "已完成"
		"failed", "error" -> "失败"
		"running", "in_progress" -> "运行中"
		"pending" -> "等待中"
		"denied" -> "已拒绝"
		else -> status
	}

private fun runStatusLabel(status: String): String =
	when (status.uppercase()) {
		"COMPLETED" -> "已完成"
		"FAILED" -> "失败"
		"CANCELED" -> "已取消"
		"INTERRUPTED" -> "已中断"
		"EXPIRED" -> "已过期"
		"RUNNING" -> "运行中"
		"WAITING_APPROVAL" -> "等待审批"
		"SENDING" -> "发送中"
		else -> status
	}

private fun String.truncateMiddle(maxLength: Int): String {
	if (length <= maxLength) return this
	val head = (maxLength - 1) / 2
	val tail = maxLength - 1 - head
	return take(head) + "…" + takeLast(tail)
}

/**
 * 将审批详情压成一行，突出工具、状态和最终决策。
 */
private fun RunApprovalInfo.approvalLine(): String =
	"$toolName / $status / ${decision ?: "未决策"}"

/**
 * 能力目录在运行面板里只展示汇总，避免把完整工具清单重复塞进辅助面板。
 */
private fun CapabilityStatusResult.summaryText(): String =
	"常驻 $visibleCount / 按需 $deferredCount / 禁用 $disabledCount"

/**
 * 详情面板中的单个信息块。
 */
@Composable
private fun DetailCard(title: String, detail: String) {
	Card(
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Background),
	) {
		Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
			Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
		}
	}
}
