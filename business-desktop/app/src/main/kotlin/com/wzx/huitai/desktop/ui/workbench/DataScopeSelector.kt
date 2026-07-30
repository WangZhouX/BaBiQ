package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
                FilterChip(selected = scope == option, onClick = { onScopeSelected(option) }, label = {
                    Text(when (option) { BusinessWorkbenchScope.ALL -> "全部"; BusinessWorkbenchScope.PERSONAL -> "我的"; BusinessWorkbenchScope.TEAM -> "团队" })
                }, modifier = Modifier.testTag(WorkbenchTags.scopeItem(option.name)))
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
                    FilterChip(
                        selected = id == teamId,
                        onClick = { onTeamSelected(id) },
                        label = { Text(name) },
                        modifier = Modifier.testTag("business-workbench-team-$id"),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = roleCode == null,
                    onClick = { onRoleSelected(null) },
                    label = { Text("不限角色") },
                    modifier = Modifier.testTag("business-workbench-role-all"),
                )
                roles.forEach { role ->
                    FilterChip(
                        selected = role.roleCode == roleCode,
                        onClick = { onRoleSelected(role.roleCode) },
                        label = { Text(role.name) },
                        modifier = Modifier.testTag("business-workbench-role-${role.roleCode}"),
                    )
                }
            }
        }
    }
}
