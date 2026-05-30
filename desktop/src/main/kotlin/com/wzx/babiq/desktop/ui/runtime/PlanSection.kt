package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.wzx.babiq.desktop.state.PlanUiState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 右侧计划区的纯展示模型。
 *
 * Composable 只消费这个模型；测试可以直接验证 build 函数，避免把视觉回归绑死在 Compose 渲染细节上。
 *
 * @property visible 是否存在需要展示的未完成计划。
 * @property title 进度标题，例如“进度 1/3”。
 * @property rows 每个步骤对应一行，已包含图标、文案和 active 标记。
 */
data class PlanSectionModel(
	val visible: Boolean,
	val title: String,
	val rows: List<PlanSectionRow>,
)

/**
 * 计划步骤行展示模型。
 *
 * @property icon 三态图标：完成、进行中、待办。
 * @property text 展示文案；进行中时优先使用 activeForm。
 * @property status 原始步骤状态，便于后续扩展颜色或无障碍描述。
 * @property active 是否是当前正在执行的步骤。
 */
data class PlanSectionRow(
	val icon: String,
	val text: String,
	val status: String,
	val active: Boolean,
)

/**
 * 将 reducer 中的计划状态转换为右侧面板模型。
 */
fun buildPlanSectionModel(state: PlanUiState): PlanSectionModel {
	val plan = state.current ?: return PlanSectionModel(visible = false, title = "", rows = emptyList())
	val rows = plan.steps.map { step ->
		val status = step.status.lowercase()
		val active = status == "in_progress"
		PlanSectionRow(
			icon = when (status) {
				"completed" -> "●"
				"in_progress" -> "◐"
				else -> "○"
			},
			text = if (active && !step.activeForm.isNullOrBlank()) step.activeForm else step.description,
			status = status,
			active = active,
		)
	}
	return PlanSectionModel(
		visible = rows.isNotEmpty(),
		title = "进度 ${state.completedCount}/${state.totalCount}",
		rows = rows,
	)
}

/**
 * 构建计划收起后的顶部提醒胶囊文案；没有计划或未收起时不显示。
 */
fun buildPlanReminderPill(state: PlanUiState): String? {
	if (!state.visible || !state.collapsed || state.totalCount == 0) {
		return null
	}
	return "◐ 计划进行中 · ${state.completedCount}/${state.totalCount} 展开"
}

/**
 * 渲染右侧运行面板中的计划区域。
 */
@Composable
fun PlanSection(state: PlanUiState) {
	val model = buildPlanSectionModel(state)
	if (!model.visible) {
		return
	}
	Card(
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Background),
	) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text("计划", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
			Text(model.title, style = MaterialTheme.typography.labelMedium, color = BaBiQColors.Muted)
			model.rows.forEach { row ->
				PlanRow(row)
			}
		}
	}
}

/**
 * 单行步骤渲染。进行中步骤使用轻微底色，帮助用户快速定位当前动作。
 */
@Composable
private fun PlanRow(row: PlanSectionRow) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(
				color = if (row.active) BaBiQColors.Accent.copy(alpha = 0.08f) else BaBiQColors.Background,
				shape = RoundedCornerShape(6.dp),
			)
			.padding(horizontal = 8.dp, vertical = 6.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(row.icon, color = if (row.active) BaBiQColors.Accent else BaBiQColors.Muted)
		Text(
			row.text,
			style = MaterialTheme.typography.bodySmall.copy(
				fontWeight = if (row.active) FontWeight.SemiBold else FontWeight.Normal,
			),
		)
	}
}
