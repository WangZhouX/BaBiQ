package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun QuickEntranceCard(
    data: JsonElement?,
    onOpen: (String) -> Unit = {},
    order: List<String> = emptyList(),
    onSortChange: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val entries = data.asObjectList()
        .filterNot { it.boolean("enabled") == false }
        .filter { it.text("path") in SAFE_WORKBENCH_PATHS }
        .orderedByIds(order)
    val pageCount = ((entries.size + QUICK_PAGE_SIZE - 1) / QUICK_PAGE_SIZE).coerceAtLeast(1)
    var pageIndex by remember(entries) { mutableIntStateOf(0) }
    if (pageIndex >= pageCount) pageIndex = 0
    val visible = entries.drop(pageIndex * QUICK_PAGE_SIZE).take(QUICK_PAGE_SIZE)
    Card(modifier.fillMaxWidth().testTag(WorkbenchTags.QUICK_ENTRANCES)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("快捷入口")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                visible.forEachIndexed { index, value ->
                    val title = value.text("title") ?: value.text("name") ?: "入口 ${index + 1}"
                    val path = value.text("path") ?: return@forEachIndexed
                    AssistChip(onClick = { onOpen(path) }, label = { Text(title) }, modifier = Modifier.testTag(WorkbenchTags.quickItem(index)))
                    val id = value.text("id")
                    val absoluteIndex = pageIndex * QUICK_PAGE_SIZE + index
                    if (id != null && absoluteIndex < entries.lastIndex) {
                        IconButton(
                            onClick = {
                                val ids = entries.mapNotNull { it.text("id") }.toMutableList()
                                val current = ids.indexOf(id)
                                if (current >= 0 && current < ids.lastIndex) {
                                    val next = ids[current + 1]
                                    ids[current + 1] = id
                                    ids[current] = next
                                    onSortChange(ids)
                                }
                            },
                            modifier = Modifier.testTag("business-workbench-quick-sort-down-$id"),
                        ) { Text("↓") }
                    }
                }
                if (visible.isEmpty()) Text("暂无快捷入口", modifier = Modifier.testTag(WorkbenchTags.QUICK_EMPTY))
            }
            if (pageCount > 1) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { pageIndex = if (pageIndex == 0) pageCount - 1 else pageIndex - 1 },
                        modifier = Modifier.testTag(WorkbenchTags.QUICK_PREVIOUS),
                    ) { Text("‹") }
                    Text("${pageIndex + 1}/$pageCount")
                    IconButton(
                        onClick = { pageIndex = (pageIndex + 1) % pageCount },
                        modifier = Modifier.testTag(WorkbenchTags.QUICK_NEXT),
                    ) { Text("›") }
                }
            }
        }
    }
}

private fun List<JsonObject>.orderedByIds(order: List<String>): List<JsonObject> {
    if (order.isEmpty()) return this
    val positions = order.withIndex().associate { it.value to it.index }
    return withIndex().sortedWith(
        compareBy<IndexedValue<JsonObject>> { positions[it.value.text("id")] ?: Int.MAX_VALUE }
            .thenBy { it.index },
    ).map { it.value }
}

private const val QUICK_PAGE_SIZE = 10

private val SAFE_WORKBENCH_PATHS = setOf(
    "/",
    "/index",
    "/index/unfinished",
    "/lawoa",
    "/bpm",
    "/approval",
    "/case",
    "/administration",
    "/management",
    "/customer",
    "/cost",
    "/consultant",
    "/lawyer-admin",
    "/tools",
    "/team",
)

private fun JsonElement?.asObjectList(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> listOf(this)
    else -> emptyList()
}

internal fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
