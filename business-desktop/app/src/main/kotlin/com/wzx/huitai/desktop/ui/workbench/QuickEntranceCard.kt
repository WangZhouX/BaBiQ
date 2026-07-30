package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 快捷入口复刻 Web 的白底 180dp 卡片和最多十个 56dp 图标。
 *
 * 接口只允许安全本地 path；远程 icon 句柄不在 Compose 端解引用，已知入口使用打包的
 * Web 原图，其余入口使用一致的蓝色占位图标。
 */
@Composable
fun QuickEntranceCard(
    data: JsonElement?,
    onOpen: (String) -> Unit = {},
    order: List<String> = emptyList(),
    onSortChange: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val remoteEntries = data.asObjectList()
        .filterNot { it.boolean("enabled") == false }
        .filter { it.text("path") in SAFE_WORKBENCH_PATHS }
    val entries = (remoteEntries.ifEmpty { DEFAULT_QUICK_ENTRANCES }).orderedByIds(order)
    val pageCount = ((entries.size + QUICK_PAGE_SIZE - 1) / QUICK_PAGE_SIZE).coerceAtLeast(1)
    var pageIndex by remember(entries) { mutableIntStateOf(0) }
    if (pageIndex >= pageCount) pageIndex = 0
    val visible = entries.drop(pageIndex * QUICK_PAGE_SIZE).take(QUICK_PAGE_SIZE)
    val firstSortableId = entries.firstOrNull()?.text("id")

    Column(
        modifier
            .fillMaxWidth()
            .background(BusinessWorkbenchVisualSpec.surface)
            .testTag(WorkbenchTags.QUICK_ENTRANCES),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⌘", color = BusinessWorkbenchVisualSpec.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text(
                " 快捷入口",
                color = BusinessWorkbenchVisualSpec.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "配置",
                color = BusinessWorkbenchVisualSpec.primary,
                modifier = Modifier
                    .testTag(firstSortableId?.let { "business-workbench-quick-sort-down-$it" } ?: "business-workbench-quick-config")
                    .clickable {
                        firstSortableId?.let { id -> onSortChange(entries.moveOneStepDown(id)) }
                    },
            )
        }
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (visible.isEmpty()) {
                Text("暂无快捷入口", modifier = Modifier.testTag(WorkbenchTags.QUICK_EMPTY))
            } else {
                Row(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    visible.forEachIndexed { index, value ->
                        QuickEntranceItem(
                            value = value,
                            index = index,
                            onOpen = onOpen,
                        )
                    }
                }
            }
            if (pageCount > 1) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .border(1.dp, BusinessWorkbenchVisualSpec.border, CircleShape)
                        .clickable { pageIndex = if (pageIndex == 0) pageCount - 1 else pageIndex - 1 }
                        .testTag(WorkbenchTags.QUICK_PREVIOUS),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("‹", color = BusinessWorkbenchVisualSpec.textSecondary)
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(1.dp, BusinessWorkbenchVisualSpec.border, CircleShape)
                        .clickable { pageIndex = (pageIndex + 1) % pageCount }
                        .testTag(WorkbenchTags.QUICK_NEXT),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (pageIndex == pageCount - 1) "‹" else "›", color = BusinessWorkbenchVisualSpec.textSecondary)
                }
            }
        }
    }
}

/** 单个入口严格使用 56dp 图标和 16sp 标题。 */
@Composable
private fun QuickEntranceItem(
    value: JsonObject,
    index: Int,
    onOpen: (String) -> Unit,
) {
    val title = value.text("configName") ?: value.text("title") ?: value.text("name") ?: "入口 ${index + 1}"
    val path = value.text("path") ?: return
    val bitmap = BusinessWorkbenchAssets.quickEntranceImage(value.text("configCode"), path)
    Column(
        modifier = Modifier
            .width(82.dp)
            .clickable { onOpen(path) }
            .testTag(WorkbenchTags.quickItem(index)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(3.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(BusinessWorkbenchVisualSpec.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.take(1),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = BusinessWorkbenchVisualSpec.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun List<JsonObject>.moveOneStepDown(id: String): List<String> {
    val ids = mapNotNull { it.text("id") }.toMutableList()
    val current = ids.indexOf(id)
    if (current >= 0 && current < ids.lastIndex) {
        val next = ids[current + 1]
        ids[current + 1] = id
        ids[current] = next
    }
    return ids
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

private val DEFAULT_QUICK_ENTRANCES = listOf(
    Triple("法律法规", "/tools", "legal_rules"),
    Triple("司法案例", "/tools", "judicial_cases"),
    Triple("法条释义", "/tools", "legal_interpretation"),
    Triple("法官问答", "/tools", "judge_qa"),
    Triple("智能计算器", "/tools", "calculator"),
    Triple("利冲检查", "/tools", "conflict_check"),
    Triple("创建案件", "/case", "case_application"),
    Triple("案件汇总表", "/case", "case_summary"),
    Triple("创建客户", "/customer", "new_customer"),
    Triple("创建拜访", "/consultant", "new_visit"),
).mapIndexed { index, (title, path, code) ->
    buildJsonObject {
        put("id", "default-$index")
        put("title", title)
        put("configCode", code)
        put("path", path)
    }
}

private val SAFE_WORKBENCH_PATHS = setOf(
    "/", "/index", "/index/unfinished", "/lawoa", "/bpm", "/approval", "/case",
    "/administration", "/management", "/customer", "/cost", "/consultant", "/lawyer-admin",
    "/tools", "/team",
)

private fun JsonElement?.asObjectList(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> listOf(this)
    else -> emptyList()
}

internal fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.boolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
