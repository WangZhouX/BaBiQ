package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 数据统计采用 Web 的四列背景图卡片，并完整展示每个统计项的细分值。
 */
@Composable
fun DataStatisticsCard(
    data: JsonElement?,
    selectedIndex: Int = 0,
    onSelected: (Int) -> Unit = {},
    order: List<String> = emptyList(),
    onSortChange: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val unsortedEntries = when (data) {
        is JsonArray -> data.mapIndexedNotNull { index, value ->
            (value as? JsonObject)?.takeUnless { it.boolean("enabled") == false }?.let { index to it }
        }
        is JsonObject -> data.takeUnless { it.boolean("enabled") == false }?.let { listOf(0 to it) }.orEmpty()
        else -> emptyList()
    }
    val positions = order.withIndex().associate { it.value to it.index }
    val entries = if (positions.isEmpty()) {
        unsortedEntries
    } else {
        unsortedEntries.withIndex()
            .sortedWith(
                compareBy<IndexedValue<Pair<Int, JsonObject>>> {
                    positions[it.value.second.text("id")] ?: Int.MAX_VALUE
                }.thenBy { it.index },
            )
            .map { it.value }
    }
    val firstSortableId = entries.firstOrNull()?.second?.text("id")

    Column(
        modifier
            .fillMaxWidth()
            .background(BusinessWorkbenchVisualSpec.surface)
            .testTag(WorkbenchTags.STATISTICS),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("◔", color = BusinessWorkbenchVisualSpec.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text(
                " 数据统计",
                color = BusinessWorkbenchVisualSpec.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "配置",
                color = BusinessWorkbenchVisualSpec.primary,
                modifier = Modifier
                    .testTag(firstSortableId?.let { "business-workbench-statistic-sort-down-$it" } ?: "business-workbench-statistic-config")
                    .clickable {
                        firstSortableId?.let { id ->
                            onSortChange(entries.mapNotNull { it.second.text("id") }.moveOneStepDown(id))
                        }
                    },
            )
        }
        if (entries.isEmpty()) {
            Text(
                "暂无统计数据",
                color = BusinessWorkbenchVisualSpec.textTertiary,
                modifier = Modifier.padding(16.dp).testTag(WorkbenchTags.STATISTICS_EMPTY),
            )
            return
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            entries.take(4).forEachIndexed { displayIndex, (sourceIndex, value) ->
                StatisticItem(
                    value = value,
                    fallbackIndex = displayIndex,
                    selected = sourceIndex == selectedIndex,
                    onClick = { onSelected(sourceIndex) },
                    modifier = Modifier.weight(1f).testTag(WorkbenchTags.statisticItem(sourceIndex)),
                )
            }
        }
    }
}

/** 单个统计卡上半区 96dp，细分项在底部横向均分。 */
@Composable
private fun StatisticItem(
    value: JsonObject,
    fallbackIndex: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val title = value.text("configName") ?: value.text("title") ?: value.text("name") ?: "统计 ${fallbackIndex + 1}"
    val total = value.text("total") ?: value.text("count") ?: value.text("value") ?: "0"
    val details = statisticDetails(value)
    Column(
        modifier
            .border(
                1.dp,
                if (selected) BusinessWorkbenchVisualSpec.primary else Color(0x29216DFF),
                RoundedCornerShape(2.dp),
            )
            .background(BusinessWorkbenchVisualSpec.surface)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected },
    ) {
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            Image(
                bitmap = BusinessWorkbenchAssets.statisticBackgroundImage(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
            Column(
                Modifier.padding(horizontal = 24.dp).align(Alignment.CenterStart),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    "$title $total",
                    color = BusinessWorkbenchVisualSpec.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        total,
                        color = BusinessWorkbenchVisualSpec.primary,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(" 个", color = BusinessWorkbenchVisualSpec.textPrimary)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .border(0.dp, Color.Transparent)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            details.forEach { detail ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(detail.label, color = BusinessWorkbenchVisualSpec.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Text(detail.value, color = detail.color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private data class StatisticDetail(val label: String, val value: String, val color: Color)

/** configCode 与 Web SUB_BUILDER 使用同一套明细映射。 */
private fun statisticDetails(value: JsonObject): List<StatisticDetail> {
    val stat = value["stat"] as? JsonObject
    fun item(label: String, key: String, color: Color) =
        StatisticDetail(label, stat?.text(key) ?: "0", color)
    return when (value.text("configCode")) {
        "case_handle" -> listOf(
            item("办理中", "handling", BusinessWorkbenchVisualSpec.success),
            item("待归档", "pendingArchive", BusinessWorkbenchVisualSpec.warning),
        )
        "appointment" -> listOf(
            item("面谈", "faceToFace", BusinessWorkbenchVisualSpec.primary),
            item("微信", "wechat", BusinessWorkbenchVisualSpec.warning),
            item("电话", "phone", BusinessWorkbenchVisualSpec.success),
        )
        "counselor_service" -> listOf(
            item("未开始", "notStarted", BusinessWorkbenchVisualSpec.warning),
            item("进行中", "inProgress", BusinessWorkbenchVisualSpec.success),
        )
        "future_visit" -> listOf(
            item("商协会", "association", BusinessWorkbenchVisualSpec.success),
            item("顾问单位", "consultantUnit", BusinessWorkbenchVisualSpec.primary),
        )
        else -> emptyList()
    }
}

private fun List<String>.moveOneStepDown(id: String): List<String> {
    val ids = toMutableList()
    val current = ids.indexOf(id)
    if (current >= 0 && current < ids.lastIndex) {
        val next = ids[current + 1]
        ids[current + 1] = id
        ids[current] = next
    }
    return ids
}
