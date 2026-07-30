package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import com.wzx.huitai.desktop.workbench.BusinessScheduleState
import com.wzx.huitai.desktop.workbench.BusinessScheduleViewMode
import java.time.DayOfWeek
import java.time.LocalDate

object ScheduleTags {
    const val ROOT = "schedule-panel-root"
    const val ICON = "schedule-icon"
    const val EMPTY_IMAGE = "schedule-empty-image"
    const val MONTH = "schedule-month"
    const val WEEK = "schedule-week"
    const val PREVIOUS = "schedule-previous"
    const val NEXT = "schedule-next"
    const val TODAY = "schedule-today"
    const val ONLY_MINE = "schedule-only-mine"
    const val CREATE = "schedule-create"

    fun eventDot(date: LocalDate) = "schedule-event-dot-$date"
    fun date(date: LocalDate) = "schedule-date-$date"
    fun complete(id: String) = "schedule-complete-$id"
}

/**
 * 日程面板默认展示选中日期所在的一周，与 Web 收起状态一致。
 *
 * MONTH 作为展开态显示完整月份；WEEK 作为默认收起态，保留原有回调语义和自动化标记。
 */
@Composable
fun SchedulePanel(
    state: BusinessScheduleState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onViewModeChanged: (BusinessScheduleViewMode) -> Unit,
    onOnlyMineChanged: (Boolean) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onCompletionChanged: (String, Boolean) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState())
            .background(BusinessWorkbenchVisualSpec.surface)
            .testTag(ScheduleTags.ROOT),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "个人日程",
                color = BusinessWorkbenchVisualSpec.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 24.dp),
            )
            Text("团队日程", color = BusinessWorkbenchVisualSpec.textPrimary, fontWeight = FontWeight.Bold)
            Text("查看更多", color = BusinessWorkbenchVisualSpec.primary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.visibleMonth.year}年${state.visibleMonth.monthValue}月",
                color = BusinessWorkbenchVisualSpec.textSecondary,
                modifier = Modifier.background(Color(0xFFFAFAFA), RoundedCornerShape(2.dp)).padding(10.dp),
            )
            Text(
                "‹",
                color = BusinessWorkbenchVisualSpec.textTertiary,
                modifier = Modifier.padding(horizontal = 10.dp).clickable(onClick = onPrevious).testTag(ScheduleTags.PREVIOUS),
            )
            Text(
                "›",
                color = BusinessWorkbenchVisualSpec.textTertiary,
                modifier = Modifier.padding(horizontal = 10.dp).clickable(onClick = onNext).testTag(ScheduleTags.NEXT),
            )
            Text(
                "今日",
                color = BusinessWorkbenchVisualSpec.textPrimary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable(onClick = onToday)
                    .testTag(ScheduleTags.TODAY)
                    .background(Color(0xFFFAFAFA), RoundedCornerShape(2.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Text(
                "+",
                color = BusinessWorkbenchVisualSpec.textPrimary,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable(onClick = onCreate)
                    .testTag(ScheduleTags.CREATE)
                    .background(Color(0xFFFAFAFA), RoundedCornerShape(2.dp))
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }
        Calendar(
            state = state,
            onDateSelected = onDateSelected,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                if (state.viewMode == BusinessScheduleViewMode.MONTH) "⌃" else "⌄",
                color = BusinessWorkbenchVisualSpec.primary,
                modifier = Modifier
                    .clickable {
                        onViewModeChanged(
                            if (state.viewMode == BusinessScheduleViewMode.MONTH) {
                                BusinessScheduleViewMode.WEEK
                            } else {
                                BusinessScheduleViewMode.MONTH
                            },
                        )
                    }
                    .testTag(
                        if (state.viewMode == BusinessScheduleViewMode.MONTH) ScheduleTags.WEEK else ScheduleTags.MONTH,
                    ),
            )
        }
        if (state.scope == BusinessWorkbenchScope.TEAM) {
            Row(
                Modifier
                    .clickable { onOnlyMineChanged(!state.onlyMine) }
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag(ScheduleTags.ONLY_MINE),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = state.onlyMine, onCheckedChange = onOnlyMineChanged)
                Text("仅查看我的", color = BusinessWorkbenchVisualSpec.textSecondary)
            }
        }
        ScheduleTimeline(
            state = state,
            onCompletionChanged = onCompletionChanged,
            onCreate = onCreate,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        state.error?.let {
            Text(it, color = BusinessWorkbenchVisualSpec.danger, modifier = Modifier.padding(16.dp))
        }
    }
}

/** 日历表头从周日开始，对齐 Web 的 WEEK_LABELS。 */
@Composable
private fun Calendar(
    state: BusinessScheduleState,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                Text(
                    it,
                    color = BusinessWorkbenchVisualSpec.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 5.dp),
                )
            }
        }
        visibleDates(state).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val selected = date == state.selectedDate
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (selected) BusinessWorkbenchVisualSpec.primary else Color.Transparent)
                                .clickable { onDateSelected(date) }
                                .testTag(ScheduleTags.date(date)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                date.dayOfMonth.toString(),
                                color = if (selected) Color.White else BusinessWorkbenchVisualSpec.textPrimary,
                            )
                        }
                        if (date in state.eventDates) {
                            Box(
                                Modifier
                                    .size(4.dp)
                                    .align(Alignment.BottomCenter)
                                    .clip(CircleShape)
                                    .background(if (selected) Color.White else BusinessWorkbenchVisualSpec.primary)
                                    .testTag(ScheduleTags.eventDot(date)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 空状态使用 Web 原图；有数据时采用时间轴列表。 */
@Composable
private fun ScheduleTimeline(
    state: BusinessScheduleState,
    onCompletionChanged: (String, Boolean) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier,
) {
    if (state.items.isEmpty()) {
        Column(
            modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                bitmap = BusinessWorkbenchAssets.scheduleEmptyImage(),
                contentDescription = "暂无日程",
                modifier = Modifier.size(170.dp).testTag(ScheduleTags.EMPTY_IMAGE),
            )
            Row {
                Text("当日暂无日程", color = BusinessWorkbenchVisualSpec.textPrimary)
                Text("，点击", color = BusinessWorkbenchVisualSpec.textPrimary)
                Text("添加日程", color = BusinessWorkbenchVisualSpec.primary, modifier = Modifier.clickable(onClick = onCreate))
            }
            Image(
                bitmap = BusinessWorkbenchAssets.scheduleIconImage(),
                contentDescription = "日程",
                modifier = Modifier.size(1.dp).testTag(ScheduleTags.ICON),
            )
        }
        return
    }
    LazyColumn(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        items(state.items, key = { it.id }) { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(BusinessWorkbenchVisualSpec.primary))
                    Box(Modifier.size(width = 1.dp, height = 46.dp).background(BusinessWorkbenchVisualSpec.border))
                }
                Checkbox(
                    checked = item.completed,
                    onCheckedChange = { onCompletionChanged(item.id, it) },
                    modifier = Modifier.testTag(ScheduleTags.complete(item.id)),
                )
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.title,
                            color = BusinessWorkbenchVisualSpec.textPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        item.typeTitle?.let {
                            Text(
                                it,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .background(scheduleColor(item.color) ?: BusinessWorkbenchVisualSpec.primary, RoundedCornerShape(2.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (item.allDay) "全天" else item.groupTime, color = BusinessWorkbenchVisualSpec.textSecondary)
                        Text(item.at, color = BusinessWorkbenchVisualSpec.textSecondary)
                        repetitionLabel(item.repetition)?.let { Text(it, color = BusinessWorkbenchVisualSpec.textSecondary) }
                    }
                    item.priority?.let { Text("优先级 $it", color = BusinessWorkbenchVisualSpec.textSecondary) }
                    item.expiredDays?.takeIf { it > 0 }?.let {
                        Text("已过期 $it 天", color = BusinessWorkbenchVisualSpec.danger)
                    }
                }
            }
        }
    }
    Image(
        bitmap = BusinessWorkbenchAssets.scheduleIconImage(),
        contentDescription = "日程",
        modifier = Modifier.size(1.dp).testTag(ScheduleTags.ICON),
    )
}

private fun repetitionLabel(repetition: Int?): String? = when (repetition) {
    1 -> "每天重复"
    2 -> "每周重复"
    3 -> "每月重复"
    4 -> "每年重复"
    else -> null
}

private fun scheduleColor(value: String?): Color? {
    val hex = value?.removePrefix("#") ?: return null
    val parsed = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
        6 -> Color((0xFF000000L or parsed).toInt())
        8 -> Color(parsed.toInt())
        else -> null
    }
}

/** WEEK 从周日开始只返回七天；MONTH 展开到覆盖月末的完整周。 */
private fun visibleDates(state: BusinessScheduleState): List<LocalDate> {
    if (state.viewMode == BusinessScheduleViewMode.WEEK) {
        val start = state.selectedDate.minusDays(state.selectedDate.dayOfWeek.value % 7L)
        return (0L..6L).map(start::plusDays)
    }
    val first = state.visibleMonth.atDay(1)
    val start = first.minusDays(first.dayOfWeek.value % 7L)
    val last = state.visibleMonth.atEndOfMonth()
    val end = last.plusDays((DayOfWeek.SATURDAY.value - last.dayOfWeek.value + 7L) % 7L)
    return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
}
