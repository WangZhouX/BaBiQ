package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPage
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Composable
fun BusinessListCard(
    page: BusinessWorkbenchPage?,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onCaseSelected: (String) -> Unit = {},
    kind: BusinessWorkbenchKind = BusinessWorkbenchKind.CASE,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().testTag(WorkbenchTags.LIST)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("业务列表")
            when {
                loading -> Text("加载中…", modifier = Modifier.testTag(WorkbenchTags.LIST_LOADING))
                error != null -> {
                    Text(error, modifier = Modifier.testTag(WorkbenchTags.LIST_ERROR))
                    Button(onClick = onRetry, modifier = Modifier.testTag(WorkbenchTags.LIST_RETRY)) { Text("重试") }
                }
                page == null || page.items.isEmpty() -> Text("暂无数据", modifier = Modifier.testTag(WorkbenchTags.LIST_EMPTY))
                else -> page.items.forEach { item ->
                    val itemKind = item.values.text("kind")
                        ?.let { raw -> BusinessWorkbenchKind.entries.firstOrNull { it.name == raw } }
                        ?: kind
                    Row(Modifier.fillMaxWidth().testTag(WorkbenchTags.listItem(item.id)), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (itemKind) {
                            BusinessWorkbenchKind.CASE -> {
                                Text(item.values.text("caseName") ?: item.title ?: item.applicationNumber ?: item.id)
                                Text(item.categoriesName ?: "")
                                caseFirmSummary(item.values)?.let { Text(it) }
                            }
                            BusinessWorkbenchKind.APPOINTMENT -> {
                                Text(item.values.text("name") ?: item.title ?: item.applicationNumber ?: item.id)
                                Text(
                                    listOfNotNull(
                                        appointmentMode(item.values.text("consultMode")),
                                        item.values.text("causeAction"),
                                        item.values.text("createTime"),
                                        item.values.text("appointLocation"),
                                        item.values.text("remark"),
                                    ).joinToString(" · "),
                                )
                            }
                            BusinessWorkbenchKind.COUNSELOR_SERVICE -> {
                                Text(item.values.text("serviceTitle") ?: item.title ?: item.id)
                                Text(
                                    listOfNotNull(
                                        item.values.text("serviceObjectName"),
                                        serviceStatus(item.values.text("serviceStatus")),
                                        item.values.text("totalServiceCount")?.let { "$it 项" },
                                        item.values.text("serviceStartDate"),
                                        item.values.text("serviceEndDate"),
                                    ).joinToString(" · "),
                                )
                            }
                            BusinessWorkbenchKind.VISIT -> {
                                Text(item.values.text("visitItem") ?: item.title ?: item.id)
                                Text(
                                    listOfNotNull(
                                        item.values.text("visitTime"),
                                        item.values.text("visitObjName"),
                                        item.values.text("scheduleName") ?: item.scheduleName,
                                    ).joinToString(" · "),
                                )
                            }
                        }
                        if (itemKind == BusinessWorkbenchKind.CASE) {
                            Button(onClick = { onCaseSelected(item.id) }) { Text("查看") }
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (page != null && page.total > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPrevious, enabled = page.pageNo > 1, modifier = Modifier.testTag(WorkbenchTags.PREVIOUS)) { Text("上一页") }
                    Text("第 ${page.pageNo} 页，共 ${page.total} 条")
                    Button(onClick = onNext, enabled = page.pageNo * page.pageSize < page.total, modifier = Modifier.testTag(WorkbenchTags.NEXT)) { Text("下一页") }
                }
            }
        }
    }
}

private fun caseFirmSummary(values: JsonObject): String? {
    val tenant = values["tenant"] as? JsonObject
    val firmName = tenant?.text("name")
    val teamCount = (values["teamDatas"] as? JsonArray)?.size?.takeIf { it > 0 }
    val hasLogo = values.text("logo")?.isNotBlank() == true || tenant?.text("logo")?.isNotBlank() == true
    return listOfNotNull(
        firmName,
        teamCount?.let { "$it 个团队角色" },
        if (hasLogo) "律所标识" else null,
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun appointmentMode(raw: String?): String? = when (raw) {
    "0" -> "面谈"
    "1" -> "微信"
    "2" -> "电话"
    else -> null
}

private fun serviceStatus(raw: String?): String? = when (raw) {
    "0" -> "未开始"
    "1" -> "进行中"
    "2" -> "已完成"
    else -> null
}
