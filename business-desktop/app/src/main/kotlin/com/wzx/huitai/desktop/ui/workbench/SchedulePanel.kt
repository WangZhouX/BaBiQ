package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
        modifier.padding(16.dp).testTag(ScheduleTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                bitmap = BusinessWorkbenchAssets.scheduleIconImage(),
                contentDescription = "日程",
                modifier = Modifier.size(20.dp).testTag(ScheduleTags.ICON),
            )
            Text(
                "${state.visibleMonth.year}年${state.visibleMonth.monthValue}月",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(onClick = onPrevious, modifier = Modifier.testTag(ScheduleTags.PREVIOUS)) {
                Text("上一页")
            }
            OutlinedButton(onClick = onToday, modifier = Modifier.testTag(ScheduleTags.TODAY)) {
                Text("今天")
            }
            OutlinedButton(onClick = onNext, modifier = Modifier.testTag(ScheduleTags.NEXT)) {
                Text("下一页")
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = state.viewMode == BusinessScheduleViewMode.MONTH,
                onClick = { onViewModeChanged(BusinessScheduleViewMode.MONTH) },
                label = { Text("月") },
                modifier = Modifier.testTag(ScheduleTags.MONTH),
            )
            FilterChip(
                selected = state.viewMode == BusinessScheduleViewMode.WEEK,
                onClick = { onViewModeChanged(BusinessScheduleViewMode.WEEK) },
                label = { Text("周") },
                modifier = Modifier.testTag(ScheduleTags.WEEK),
            )
            if (state.scope == BusinessWorkbenchScope.TEAM) {
                Row(
                    Modifier.clickable { onOnlyMineChanged(!state.onlyMine) }
                        .testTag(ScheduleTags.ONLY_MINE),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.onlyMine,
                        onCheckedChange = onOnlyMineChanged,
                    )
                    Text("只看我的")
                }
            }
            Button(onClick = onCreate, modifier = Modifier.testTag(ScheduleTags.CREATE)) {
                Text("新增日程")
            }
        }

        Card(Modifier.fillMaxWidth().weight(0.62f)) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                        Text(it, Modifier.weight(1f))
                    }
                }
                val dates = visibleDates(state)
                dates.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        week.forEach { date ->
                            val selected = date == state.selectedDate
                            Box(Modifier.weight(1f)) {
                                Column(
                                    Modifier.fillMaxSize()
                                    .clip(MaterialTheme.shapes.small)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                    )
                                    .clickable { onDateSelected(date) }
                                    .testTag(ScheduleTags.date(date))
                                    .padding(5.dp),
                                ) {
                                    Text(date.dayOfMonth.toString())
                                }
                                if (date in state.eventDates) {
                                    Box(
                                        Modifier.size(7.dp)
                                            .align(Alignment.BottomStart)
                                            .clip(MaterialTheme.shapes.small)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .testTag(ScheduleTags.eventDot(date)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.items.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().weight(0.38f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    bitmap = BusinessWorkbenchAssets.scheduleEmptyImage(),
                    contentDescription = "暂无日程",
                    modifier = Modifier.size(92.dp).testTag(ScheduleTags.EMPTY_IMAGE),
                )
                Text("当日暂无日程", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(0.38f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.items, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = item.completed,
                                onCheckedChange = { onCompletionChanged(item.id, it) },
                                modifier = Modifier.testTag(ScheduleTags.complete(item.id)),
                            )
                            Column {
                                Text(if (item.allDay) "全天" else item.groupTime)
                                Text(item.title)
                                item.typeTitle?.let { typeTitle ->
                                    Text(
                                        typeTitle,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .background(
                                                scheduleColor(item.color)
                                                    ?: MaterialTheme.colorScheme.primary,
                                                MaterialTheme.shapes.small,
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                Text(item.at, style = MaterialTheme.typography.bodySmall)
                                item.priority?.let { Text("优先级 $it", style = MaterialTheme.typography.bodySmall) }
                                repetitionLabel(item.repetition)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                                item.expiredDays?.takeIf { it > 0 }?.let {
                                    Text("已过期 $it 天", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
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

private fun visibleDates(state: BusinessScheduleState): List<LocalDate> {
    if (state.viewMode == BusinessScheduleViewMode.WEEK) {
        val start = state.selectedDate.minusDays(
            (state.selectedDate.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong(),
        )
        return (0L..6L).map(start::plusDays)
    }
    val first = state.visibleMonth.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    return (0L until 42L).map(start::plusDays)
}
