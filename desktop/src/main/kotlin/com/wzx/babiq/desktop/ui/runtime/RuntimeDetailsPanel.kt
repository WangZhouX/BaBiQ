package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.RunApprovalInfo
import com.wzx.babiq.desktop.protocol.RunToolCallInfo
import com.wzx.babiq.desktop.protocol.RunTurnDetailResult
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.RunRecordState
import com.wzx.babiq.desktop.state.RunTurnListItem
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 右侧运行详情面板。
 *
 * 它展示和聊天主区同一份状态：当前 turn 状态、最近成本摘要、实时事件，以及 P2-4 持久化后的历史运行记录。
 */
@Composable
fun RuntimeDetailsPanel(
	state: AppState,
	modifier: Modifier = Modifier,
	onClose: () -> Unit,
	onSelectRunTurn: (String) -> Unit,
) {
	Column(
		modifier = modifier
			.fillMaxHeight()
			.background(BaBiQColors.Panel)
			.padding(16.dp)
			.verticalScroll(rememberScrollState()),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
			Text("运行详情", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
			TextButton(onClick = onClose) { Text("收起") }
		}
		RunRecordSection(
			state = state.runRecordState,
			onSelectRunTurn = onSelectRunTurn,
		)
		// 成本摘要在这里作为详情复用；聊天流里的 TurnSummaryBar 仍然是主展示位置。
		state.latestSummary?.let { TurnSummaryBar(it) }
		DetailCard("当前状态", "${state.turnState} / ${state.connectionState}")
		state.runtimeEvents.forEach { event ->
			DetailCard(event.title, event.detail + event.raw?.let { "\n$it" }.orEmpty())
		}
		if (state.runtimeEvents.isEmpty() && state.latestSummary == null) {
			Text("暂无运行详情。完成一轮任务后，这里会显示工具轨迹和成本明细。", color = BaBiQColors.Muted)
		}
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
		RunTurnDetail(detail)
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
private fun RunTurnDetail(detail: RunTurnDetailResult) {
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
private fun RunToolCallInfo.toolLine(): String =
	"$toolName / $status / ${errorMessage ?: resultPreview ?: "无预览"}"

/**
 * 将审批详情压成一行，突出工具、状态和最终决策。
 */
private fun RunApprovalInfo.approvalLine(): String =
	"$toolName / $status / ${decision ?: "未决策"}"

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
