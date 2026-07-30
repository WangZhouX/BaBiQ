package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
        is JsonObject -> data.takeUnless { it.boolean("enabled") == false }
            ?.let { listOf(0 to it) }
            ?: emptyList()
        else -> emptyList()
    }
    val positions = order.withIndex().associate { it.value to it.index }
    val entries = if (positions.isEmpty()) unsortedEntries else unsortedEntries.withIndex()
        .sortedWith(
            compareBy<IndexedValue<Pair<Int, JsonObject>>> {
                positions[it.value.second.text("id")] ?: Int.MAX_VALUE
            }.thenBy { it.index },
        )
        .map { it.value }
    FlowRow(
        modifier = modifier.fillMaxWidth().testTag(WorkbenchTags.STATISTICS),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEachIndexed { displayIndex, (index, value) ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelected(index) },
                label = {
                    Text(
                        "${value.text("title") ?: value.text("name") ?: value.text("configName") ?: "统计 ${index + 1}"} " +
                            "${value.text("count") ?: value.text("value") ?: value.text("total") ?: "0"}",
                    )
                },
                modifier = Modifier.testTag(WorkbenchTags.statisticItem(index)),
            )
            val id = value.text("id")
            if (id != null && displayIndex < entries.lastIndex) {
                IconButton(
                    onClick = {
                        val ids = entries.mapNotNull { it.second.text("id") }.toMutableList()
                        val current = ids.indexOf(id)
                        if (current >= 0 && current < ids.lastIndex) {
                            val next = ids[current + 1]
                            ids[current + 1] = id
                            ids[current] = next
                            onSortChange(ids)
                        }
                    },
                    modifier = Modifier.testTag("business-workbench-statistic-sort-down-$id"),
                ) { Text("↓") }
            }
        }
        if (entries.isEmpty()) Text("暂无统计数据", modifier = Modifier.testTag(WorkbenchTags.STATISTICS_EMPTY))
    }
}
