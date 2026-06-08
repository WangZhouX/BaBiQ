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
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.CapabilityUiState
import com.wzx.babiq.desktop.state.MemoryUiState
import com.wzx.babiq.desktop.state.ObservabilityState
import com.wzx.babiq.desktop.state.RunRecordState
import com.wzx.babiq.desktop.state.RunTurnListItem
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

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

fun runtimePanelTabs(state: AppState, selectedTab: RuntimePanelTab): List<RuntimePanelTabItem> {
	val resolved = resolveRuntimePanelTab(state, selectedTab)
	return listOfNotNull(
		RuntimePanelTabItem(RuntimePanelTab.Run, "运行", visible = true, selected = resolved == RuntimePanelTab.Run),
		RuntimePanelTabItem(
			RuntimePanelTab.Orchestration,
			"编排",
			visible = state.orchestrationState.visible,
			selected = resolved == RuntimePanelTab.Orchestration,
		).takeIf { it.visible },
		RuntimePanelTabItem(
			RuntimePanelTab.Team,
			"团队",
			visible = state.teamState.visible,
			selected = resolved == RuntimePanelTab.Team,
		).takeIf { it.visible },
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
		RuntimePanelTab.Orchestration -> requested.takeIf { state.orchestrationState.visible } ?: RuntimePanelTab.Run
		RuntimePanelTab.Team -> requested.takeIf { state.teamState.visible } ?: RuntimePanelTab.Run
		RuntimePanelTab.SubAgent -> requested.takeIf { state.subAgentState.visible } ?: RuntimePanelTab.Run
		RuntimePanelTab.Run -> RuntimePanelTab.Run
	}

fun preferredRuntimePanelTab(state: AppState, current: RuntimePanelTab): RuntimePanelTab =
	when {
		state.orchestrationState.configuringWorkUnit != null -> RuntimePanelTab.Orchestration
		state.teamState.configuringWorkUnit != null -> RuntimePanelTab.Team
		else -> resolveRuntimePanelTab(state, current)
	}

fun runtimePanelContent(tab: RuntimePanelTab): Set<RuntimePanelContent> =
	when (tab) {
		RuntimePanelTab.Run -> setOf(
			RuntimePanelContent.Plan,
			RuntimePanelContent.WorkUnits,
			RuntimePanelContent.Environment,
			RuntimePanelContent.Context,
			RuntimePanelContent.RunRecords,
			RuntimePanelContent.Observability,
			RuntimePanelContent.Summary,
			RuntimePanelContent.Status,
			RuntimePanelContent.Events,
			RuntimePanelContent.EmptyState,
		)
		RuntimePanelTab.Orchestration -> setOf(RuntimePanelContent.Orchestration)
		RuntimePanelTab.Team -> setOf(RuntimePanelContent.Team)
		RuntimePanelTab.SubAgent -> setOf(RuntimePanelContent.SubAgent)
	}

@Composable
fun RuntimeDetailsPanel(
	state: AppState,
	modifier: Modifier = Modifier,
	onClose: () -> Unit,
	onDismissSubAgent: () -> Unit = {},
	onSelectWorkUnit: (String) -> Unit = {},
	onConfigureWorkUnit: (String) -> Unit = {},
	onStartWorkUnit: (String) -> Unit = {},
	onRemoveWorkUnit: (String) -> Unit = {},
	onUpdateWorkUnitGoal: (String, String, String) -> Unit = { _, _, _ -> },
	onUpdateWorkUnitConfig: (String, String) -> Unit = { _, _ -> },
	onSendTeamMessage: (String, String) -> Unit = { _, _ -> },
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
			when (effectiveTab) {
				RuntimePanelTab.Run -> {
			PlanSection(state.planState)
			WorkUnitSection(
				state.workUnitState,
				onSelect = onSelectWorkUnit,
				onConfigure = onConfigureWorkUnit,
				onStart = onStartWorkUnit,
				onRemove = onRemoveWorkUnit,
				onUpdateGoal = onUpdateWorkUnitGoal,
			)
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
				RuntimePanelTab.Orchestration -> OrchestrationSection(
					state = state.orchestrationState,
					modelLabel = state.providerState.active.label,
					providerState = state.providerState,
					onStartWorkUnit = onStartWorkUnit,
					onUpdateWorkUnitGoal = onUpdateWorkUnitGoal,
					onUpdateWorkUnitConfig = onUpdateWorkUnitConfig,
				)
				RuntimePanelTab.Team -> TeamSection(
					state = state.teamState,
					modelLabel = state.providerState.active.label,
					providerState = state.providerState,
					onStartWorkUnit = onStartWorkUnit,
					onUpdateWorkUnitGoal = onUpdateWorkUnitGoal,
					onUpdateWorkUnitConfig = onUpdateWorkUnitConfig,
					onSendTeamMessage = onSendTeamMessage,
				)
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
		Text("历史 turn", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
		state.turns.forEach { turn ->
			RunTurnRow(
				turn = turn,
				selected = turn.turnId == state.selectedTurnId,
				onSelectRunTurn = onSelectRunTurn,
			)
		}
	}
	state.selectedDetail?.let { detail ->
		RunTurnDetail(
			detail = detail,
			memoryState = memoryState,
			capabilityState = capabilityState,
		)
	}
}

/**
 * 历史 turn 列表项。
 */
@Composable
private fun RunTurnRow(
	turn: RunTurnListItem,
	selected: Boolean,
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
				TextButton(onClick = { onSelectRunTurn(turn.turnId) }) { Text("查看") }
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
	memoryState: MemoryUiState,
	capabilityState: CapabilityUiState,
) {
	DetailCard(
		title = "选中 turn",
		detail = buildString {
			append("id: ").append(detail.turn.turnId)
			append("\n状态: ").append(detail.turn.status)
			append("\n输入: ").append(detail.turn.inputText)
			detail.turn.recoveryReason?.let { append("\n恢复原因: ").append(it) }
		},
	)
	detail.summary?.let { TurnSummaryBar(it) }
	detail.contextSnapshot?.let { snapshot -> ContextSnapshotSection(snapshot) }
	MemoryReferenceSection(memoryState)
	CapabilitySearchAuditSection(capabilityState)
	if (detail.toolCalls.isNotEmpty()) {
		DetailCard("工具调用", detail.toolCalls.joinToString("\n") { it.toolLine() })
	}
	if (detail.approvals.isNotEmpty()) {
		DetailCard("审批记录", detail.approvals.joinToString("\n") { it.approvalLine() })
	}
	if (detail.items.isNotEmpty()) {
		DetailCard("协议 item", "共 ${detail.items.size} 条")
	}
}

/**
 * 将工具调用详情压成一行，避免右侧面板被长 JSON 输出撑开。
 */
private fun RunToolCallInfo.toolLine(): String {
	val agentPrefix = agentName
		?.takeIf { it.isNotBlank() }
		?.let { agent -> "[$agent] " }
		.orEmpty()
	val delegationSuffix = delegationId
		?.takeIf { it.isNotBlank() }
		?.let { delegation -> " / delegation $delegation" }
		.orEmpty()
	return "$agentPrefix$toolName / $status / ${errorMessage ?: resultPreview ?: "无预览"}$delegationSuffix"
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
