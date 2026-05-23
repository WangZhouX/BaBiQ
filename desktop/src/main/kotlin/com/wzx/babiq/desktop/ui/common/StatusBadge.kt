package com.wzx.babiq.desktop.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 小型状态徽标。
 *
 * 项目、权限、连接状态、模型选择都复用它，保证上下文条视觉一致。
 */
@Composable
fun StatusBadge(
	text: String,
	tone: BadgeTone = BadgeTone.Neutral,
	modifier: Modifier = Modifier,
) {
	val color = when (tone) {
		BadgeTone.Neutral -> BaBiQColors.Border
		BadgeTone.Info -> Color(0xFFDCE7F7)
		BadgeTone.Success -> Color(0xFFDDEDE5)
		BadgeTone.Warning -> Color(0xFFF3E4CF)
		BadgeTone.Danger -> Color(0xFFF2D8D8)
	}
	Box(
		modifier = modifier
			.background(color, RoundedCornerShape(8.dp))
			.padding(horizontal = 9.dp, vertical = 5.dp),
	) {
		Text(text = text, style = MaterialTheme.typography.labelMedium)
	}
}

/**
 * Badge 的语义色调。
 */
enum class BadgeTone {
	Neutral,
	Info,
	Success,
	Warning,
	Danger,
}
