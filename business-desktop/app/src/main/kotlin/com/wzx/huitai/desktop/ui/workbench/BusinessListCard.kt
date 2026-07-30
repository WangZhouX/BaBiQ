package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPage
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPageItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 业务列表复刻 Web 工作台的紧凑横排列表。
 *
 * 案件行使用“主信息 | 案件类别 | 案由 | 代理流程”四块布局；其它业务类型继续只展示
 * 各自白名单字段，避免跨类型误读远程响应。
 */
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
    Column(
        modifier
            .fillMaxSize()
            .background(BusinessWorkbenchVisualSpec.surface)
            .testTag(WorkbenchTags.LIST),
    ) {
        when {
            loading -> Text(
                "加载中…",
                color = BusinessWorkbenchVisualSpec.textSecondary,
                modifier = Modifier.padding(18.dp).testTag(WorkbenchTags.LIST_LOADING),
            )
            error != null -> Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(error, color = BusinessWorkbenchVisualSpec.danger, modifier = Modifier.testTag(WorkbenchTags.LIST_ERROR))
                Text(
                    "重试",
                    color = BusinessWorkbenchVisualSpec.primary,
                    modifier = Modifier.clickable(onClick = onRetry).testTag(WorkbenchTags.LIST_RETRY),
                )
            }
            page == null || page.items.isEmpty() ->
                Text(
                    "暂无数据",
                    color = BusinessWorkbenchVisualSpec.textTertiary,
                    modifier = Modifier.padding(18.dp).testTag(WorkbenchTags.LIST_EMPTY),
                )
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(page.items, key = { it.id }) { item ->
                    val itemKind = item.values.text("kind")
                        ?.let { raw -> BusinessWorkbenchKind.entries.firstOrNull { it.name == raw } }
                        ?: kind
                    if (itemKind == BusinessWorkbenchKind.CASE) {
                        CaseListItem(item, onCaseSelected)
                    } else {
                        SimpleBusinessItem(item, itemKind)
                    }
                    HorizontalDivider(color = BusinessWorkbenchVisualSpec.divider)
                }
            }
        }
        if (page != null && page.total > 0) {
            Pagination(page, onPrevious, onNext)
        }
    }
}

/** 案件主信息与三个统计列同 Web 一致横向排列。 */
@Composable
private fun CaseListItem(item: BusinessWorkbenchPageItem, onCaseSelected: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCaseSelected(item.id) }
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag(WorkbenchTags.listItem(item.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.primaryTitle(BusinessWorkbenchKind.CASE),
                    color = BusinessWorkbenchVisualSpec.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                levelBadge(item.values.text("caseLevel"))
                statusBadge(item.values.text("status"))
                Text(
                    "查看",
                    color = BusinessWorkbenchVisualSpec.primary,
                    modifier = Modifier.clickable { onCaseSelected(item.id) },
                )
            }
            Text(
                caseTimeLine(item),
                color = BusinessWorkbenchVisualSpec.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                caseParties(item.values),
                color = BusinessWorkbenchVisualSpec.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            caseFirmSummary(item.values)?.let {
                Text(
                    it,
                    color = BusinessWorkbenchVisualSpec.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        VerticalListDivider()
        CaseStat(item.categoriesName ?: item.values.text("caseCategory") ?: "-", "案件类别")
        VerticalListDivider()
        CaseStat(item.values.text("caseReason") ?: "-", "案由")
        VerticalListDivider()
        CaseStat(item.values.text("agentProcess")?.replace(",", "、") ?: "-", "代理流程")
    }
}

/** 非案件列表沿用紧凑两行结构，不渲染“查看”按钮。 */
@Composable
private fun SimpleBusinessItem(item: BusinessWorkbenchPageItem, kind: BusinessWorkbenchKind) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(WorkbenchTags.listItem(item.id)),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(item.primaryTitle(kind), color = BusinessWorkbenchVisualSpec.textPrimary, fontWeight = FontWeight.SemiBold)
        Text(item.secondaryLine(kind), color = BusinessWorkbenchVisualSpec.textSecondary)
    }
}

@Composable
private fun VerticalListDivider() {
    Box(
        Modifier
            .padding(horizontal = 18.dp)
            .width(1.dp)
            .height(50.dp)
            .background(BusinessWorkbenchVisualSpec.border),
    )
}

@Composable
private fun CaseStat(value: String, label: String) {
    Column(
        Modifier.width(150.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            value,
            color = BusinessWorkbenchVisualSpec.textPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(label, color = BusinessWorkbenchVisualSpec.textTertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Pagination(page: BusinessWorkbenchPage, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("共 ${page.total} 条", color = BusinessWorkbenchVisualSpec.textSecondary)
        PageButton(
            "‹",
            enabled = page.pageNo > 1,
            onClick = onPrevious,
            modifier = Modifier.padding(start = 16.dp).testTag(WorkbenchTags.PREVIOUS),
        )
        Text(
            page.pageNo.toString(),
            color = Color.White,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .background(BusinessWorkbenchVisualSpec.primary, RoundedCornerShape(2.dp))
                .padding(horizontal = 9.dp, vertical = 5.dp),
        )
        PageButton(
            "›",
            enabled = page.pageNo * page.pageSize < page.total,
            onClick = onNext,
            modifier = Modifier.testTag(WorkbenchTags.NEXT),
        )
    }
}

@Composable
private fun PageButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        color = if (enabled) BusinessWorkbenchVisualSpec.textSecondary else BusinessWorkbenchVisualSpec.border,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun statusBadge(status: String?) {
    val label = statusLabel(status)
    val color = when (status) {
        "2" -> BusinessWorkbenchVisualSpec.warning
        "3" -> BusinessWorkbenchVisualSpec.primary
        else -> BusinessWorkbenchVisualSpec.success
    }
    Text(
        label,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(2.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun levelBadge(level: String?) {
    if (level.isNullOrBlank()) return
    val color = when (level) {
        "非常重要" -> Color(0xFFF5222D)
        "重要" -> Color(0xFFFA8C16)
        "次要" -> BusinessWorkbenchVisualSpec.textTertiary
        else -> BusinessWorkbenchVisualSpec.primary
    }
    Text(
        level,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.background(color.copy(alpha = 0.08f), RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

private fun BusinessWorkbenchPageItem.primaryTitle(kind: BusinessWorkbenchKind) = when (kind) {
    BusinessWorkbenchKind.CASE -> values.text("caseName") ?: title ?: applicationNumber ?: id
    BusinessWorkbenchKind.APPOINTMENT -> values.text("name") ?: title ?: applicationNumber ?: id
    BusinessWorkbenchKind.COUNSELOR_SERVICE -> values.text("serviceTitle") ?: title ?: id
    BusinessWorkbenchKind.VISIT -> values.text("visitItem") ?: title ?: id
}

private fun BusinessWorkbenchPageItem.secondaryLine(kind: BusinessWorkbenchKind) = when (kind) {
    BusinessWorkbenchKind.CASE -> caseTimeLine(this)
    BusinessWorkbenchKind.APPOINTMENT -> listOfNotNull(
        appointmentMode(values.text("consultMode")),
        values.text("causeAction"),
        values.text("createTime"),
        values.text("appointLocation"),
        values.text("remark"),
    ).joinToString(" · ")
    BusinessWorkbenchKind.COUNSELOR_SERVICE -> listOfNotNull(
        values.text("serviceObjectName"),
        serviceStatus(values.text("serviceStatus")),
        values.text("totalServiceCount")?.let { "$it 项" },
        values.text("serviceStartDate"),
        values.text("serviceEndDate"),
    ).joinToString(" · ")
    BusinessWorkbenchKind.VISIT -> listOfNotNull(
        values.text("visitTime"),
        values.text("visitObjName"),
        values.text("scheduleName") ?: scheduleName,
    ).joinToString(" · ")
}

private fun caseTimeLine(item: BusinessWorkbenchPageItem) = listOf(
    "创建时间：${item.values.text("createTime") ?: "-"}",
    "收案时间：${item.values.text("acceptTime") ?: "-"}",
    "案件编号：${item.applicationNumber ?: "-"}",
).joinToString("  |  ")

private fun caseParties(values: JsonObject): String {
    val parties = (values["parties"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.text("name") }
        .orEmpty()
    val partyCount = values.text("partyCount")?.toIntOrNull() ?: parties.size
    return if (parties.isEmpty()) {
        "当事人：-"
    } else {
        "当事人：" + parties.take(3).joinToString("、") +
            if (partyCount > 3) "...等${partyCount}个" else ""
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

private fun statusLabel(raw: String?) = when (raw) {
    "0" -> "草稿"
    "1" -> "办理中"
    "2" -> "待归档"
    "3" -> "已归档"
    else -> "办理中"
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
