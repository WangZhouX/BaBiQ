package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchTeamRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Composable
fun DataScopeSelector(
    scope: BusinessWorkbenchScope,
    onScopeSelected: (BusinessWorkbenchScope) -> Unit,
    teams: JsonElement? = null,
    teamId: String? = null,
    roles: List<BusinessWorkbenchTeamRole> = emptyList(),
    roleCode: String? = null,
    onTeamSelected: (String) -> Unit = {},
    onRoleSelected: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag(WorkbenchTags.SCOPE), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BusinessWorkbenchScope.entries.forEach { option ->
                WorkbenchFilter(
                    selected = scope == option,
                    label = when (option) {
                        BusinessWorkbenchScope.ALL -> "全部数据"
                        BusinessWorkbenchScope.PERSONAL -> "个人数据"
                        BusinessWorkbenchScope.TEAM -> "团队数据"
                    },
                    onClick = { onScopeSelected(option) },
                    modifier = Modifier.testTag(WorkbenchTags.scopeItem(option.name)),
                )
            }
        }
        if (scope == BusinessWorkbenchScope.TEAM) {
            val teamItems = when (teams) {
                is JsonArray -> teams.mapNotNull { it as? JsonObject }
                is JsonObject -> (teams["items"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(teams)
                else -> emptyList()
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                teamItems.forEach { team ->
                    val id = team.text("id") ?: team.text("teamId") ?: return@forEach
                    val name = team.text("name") ?: team.text("teamName") ?: id
                    WorkbenchFilter(
                        selected = id == teamId,
                        onClick = { onTeamSelected(id) },
                        label = name,
                        modifier = Modifier.testTag("business-workbench-team-$id"),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkbenchFilter(
                    selected = roleCode == null,
                    onClick = { onRoleSelected(null) },
                    label = "不限角色",
                    modifier = Modifier.testTag("business-workbench-role-all"),
                )
                roles.forEach { role ->
                    WorkbenchFilter(
                        selected = role.roleCode == roleCode,
                        onClick = { onRoleSelected(role.roleCode) },
                        label = role.name,
                        modifier = Modifier.testTag("business-workbench-role-${role.roleCode}"),
                    )
                }
            }
        }
    }
}

/** 轻量筛选 Tab 对齐 Web 的白底/蓝字样式，不使用 Material 紫色 FilterChip。 */
@Composable
private fun WorkbenchFilter(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        color = if (selected) BusinessWorkbenchVisualSpec.primary else BusinessWorkbenchVisualSpec.textPrimary,
        modifier = modifier
            .background(
                if (selected) Color(0xFFF5F9FF) else BusinessWorkbenchVisualSpec.surface,
                RoundedCornerShape(2.dp),
            )
            .border(
                1.dp,
                if (selected) BusinessWorkbenchVisualSpec.primary else BusinessWorkbenchVisualSpec.border,
                RoundedCornerShape(2.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
