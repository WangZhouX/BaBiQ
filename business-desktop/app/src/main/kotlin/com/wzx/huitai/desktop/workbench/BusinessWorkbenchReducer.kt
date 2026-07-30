package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Pure workbench state transitions; request ids and epochs make late responses harmless. */
class BusinessWorkbenchReducer {
    fun reduce(state: BusinessWorkbenchState, event: BusinessWorkbenchEvent): BusinessWorkbenchState = when (event) {
        is BusinessWorkbenchEvent.LoadStarted -> {
            val reset = state.identityEpoch != null && state.identityEpoch != event.identityEpoch
            state.copy(
                loadState = BusinessWorkbenchLoadState.LOADING,
                identityEpoch = event.identityEpoch,
                snapshot = if (reset) null else state.snapshot,
                navigation = if (reset) emptyList() else state.navigation,
                generation = if (reset) null else state.generation,
                error = null,
                page = if (reset) null else state.page,
                pageError = null,
                loadRequestId = event.requestId,
            )
        }
        is BusinessWorkbenchEvent.Loaded -> if (state.identityEpoch == event.snapshot.identityEpoch && state.loadRequestId == event.requestId) {
            val statistic = firstEnabledStatistic(event.snapshot)
            val authorizedTeams = teamIds(event.snapshot)
            val invalidTeam = state.scope == BusinessWorkbenchScope.TEAM &&
                (state.teamId == null || state.teamId !in authorizedTeams)
            val generationChanged = state.generation != null && state.generation != event.snapshot.generation
            val invalidateRoles = invalidTeam || generationChanged
            state.copy(
                loadState = BusinessWorkbenchLoadState.READY,
                generation = event.snapshot.generation,
                snapshot = event.snapshot,
                navigation = event.navigation,
                error = null,
                selectedStatistic = statistic?.first ?: 0,
                selectedKind = statistic?.second ?: state.selectedKind,
                scope = if (invalidTeam) BusinessWorkbenchScope.ALL else state.scope,
                teamId = if (invalidTeam) null else state.teamId,
                roleCode = if (invalidateRoles) null else state.roleCode,
                page = null,
                pageNo = 1,
                pageError = null,
                roles = if (invalidateRoles) emptyList() else state.roles,
                rolesLoading = false,
                rolesError = null,
                shortcutOrder = sectionIds(event.snapshot.shortcuts.data),
                summaryOrder = sectionIds(event.snapshot.summary.data),
                shortcutSortRevision = if (generationChanged) 0 else state.shortcutSortRevision,
                summarySortRevision = if (generationChanged) 0 else state.summarySortRevision,
                sortLoading = false,
                sortError = null,
            )
        } else state
        is BusinessWorkbenchEvent.LoadFailed -> if (state.identityEpoch == event.identityEpoch && state.loadRequestId == event.requestId) {
            state.copy(loadState = BusinessWorkbenchLoadState.ERROR, error = event.message)
        } else state
        is BusinessWorkbenchEvent.PageStarted -> if (state.identityEpoch == event.identityEpoch) {
            state.copy(pageLoading = true, pageError = null, pageRequestId = event.requestId)
        } else state
        is BusinessWorkbenchEvent.PageLoaded -> if (
            state.identityEpoch == event.identityEpoch &&
            state.pageRequestId == event.requestId &&
            event.page.identityEpoch == event.identityEpoch &&
            event.page.generation == state.generation
        ) {
            state.copy(page = event.page, pageLoading = false, pageError = null, pageNo = event.page.pageNo)
        } else state
        is BusinessWorkbenchEvent.PageFailed -> if (state.identityEpoch == event.identityEpoch && state.pageRequestId == event.requestId) {
            state.copy(pageLoading = false, pageError = event.message)
        } else state
        is BusinessWorkbenchEvent.EpochChanged -> if (state.identityEpoch == event.identityEpoch) state else clearedForEpoch(event.identityEpoch)
        is BusinessWorkbenchEvent.KindChanged -> state.copy(
            selectedKind = event.kind,
            page = null,
            pageNo = 1,
            pageError = null,
            roles = emptyList(),
            roleCode = null,
            rolesError = null,
        )
        is BusinessWorkbenchEvent.ScopeChanged -> state.copy(
            scope = event.scope,
            teamId = event.teamId,
            roleCode = event.roleCode,
            page = null,
            pageNo = 1,
            pageError = null,
            roles = if (event.scope == BusinessWorkbenchScope.TEAM && state.teamId == event.teamId) {
                state.roles
            } else {
                emptyList()
            },
            rolesError = null,
        )
        is BusinessWorkbenchEvent.StatisticSelected -> state.copy(selectedStatistic = event.index.coerceAtLeast(0))
        is BusinessWorkbenchEvent.RolesStarted -> if (state.identityEpoch == event.identityEpoch) {
            state.copy(rolesLoading = true, rolesError = null, rolesRequestId = event.requestId)
        } else state
        is BusinessWorkbenchEvent.RolesLoaded -> if (
            state.identityEpoch == event.identityEpoch &&
            state.generation == event.generation &&
            state.rolesRequestId == event.requestId &&
            state.scope == BusinessWorkbenchScope.TEAM &&
            state.teamId == event.teamId &&
            state.selectedKind == event.kind
        ) {
            val codes = event.roles.map { it.roleCode }.toSet()
            state.copy(
                roles = event.roles,
                roleCode = state.roleCode?.takeIf { it in codes },
                rolesLoading = false,
                rolesError = null,
            )
        } else state
        is BusinessWorkbenchEvent.RolesFailed -> if (
            state.identityEpoch == event.identityEpoch && state.rolesRequestId == event.requestId
        ) {
            state.copy(roles = emptyList(), roleCode = null, rolesLoading = false, rolesError = event.message)
        } else state
        is BusinessWorkbenchEvent.SortStarted -> if (
            state.identityEpoch == event.identityEpoch && state.generation == event.generation
        ) when (event.kind) {
            BusinessWorkbenchSortKind.SHORTCUT -> state.copy(
                shortcutOrder = event.optimisticIds,
                sortLoading = true,
                sortError = null,
                sortRequestId = event.requestId,
            )
            BusinessWorkbenchSortKind.SUMMARY -> state.copy(
                summaryOrder = event.optimisticIds,
                sortLoading = true,
                sortError = null,
                sortRequestId = event.requestId,
            )
        } else state
        is BusinessWorkbenchEvent.SortSucceeded -> if (
            state.identityEpoch == event.identityEpoch &&
            state.generation == event.generation &&
            state.sortRequestId == event.requestId
        ) {
            when (event.kind) {
                BusinessWorkbenchSortKind.SHORTCUT -> state.copy(
                    shortcutOrder = event.canonicalIds,
                    shortcutSortRevision = event.revision,
                    sortLoading = false,
                    sortError = null,
                )
                BusinessWorkbenchSortKind.SUMMARY -> state.copy(
                    summaryOrder = event.canonicalIds,
                    summarySortRevision = event.revision,
                    sortLoading = false,
                    sortError = null,
                )
            }
        } else state
        is BusinessWorkbenchEvent.SortFailed -> if (
            state.identityEpoch == event.identityEpoch &&
            state.generation == event.generation &&
            state.sortRequestId == event.requestId
        ) {
            when (event.kind) {
                BusinessWorkbenchSortKind.SHORTCUT -> state.copy(
                    shortcutOrder = event.rollbackIds,
                    shortcutSortRevision = event.acceptedRevision ?: state.shortcutSortRevision,
                    sortLoading = false,
                    sortError = event.message,
                )
                BusinessWorkbenchSortKind.SUMMARY -> state.copy(
                    summaryOrder = event.rollbackIds,
                    summarySortRevision = event.acceptedRevision ?: state.summarySortRevision,
                    sortLoading = false,
                    sortError = event.message,
                )
            }
        } else state
        BusinessWorkbenchEvent.Cleared -> BusinessWorkbenchState()
    }

    private fun clearedForEpoch(epoch: Long) = BusinessWorkbenchState(identityEpoch = epoch)

    private fun firstEnabledStatistic(
        snapshot: com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSnapshot,
    ): Pair<Int, BusinessWorkbenchKind>? {
        val values = when (val raw = snapshot.summary.data) {
            is JsonArray -> raw
            is JsonObject -> JsonArray(listOf(raw))
            else -> return null
        }
        return values.mapIndexedNotNull { index, raw ->
            val value = raw as? JsonObject ?: return@mapIndexedNotNull null
            if ((value["enabled"] as? JsonPrimitive)?.contentOrNull == "false") return@mapIndexedNotNull null
            statisticKind(value)?.let { index to it }
        }.firstOrNull()
    }

    private fun statisticKind(value: JsonObject): BusinessWorkbenchKind? {
        val id = (value["id"] as? JsonPrimitive)?.contentOrNull
        val code = listOf("kind", "configCode", "configName", "name", "title")
            .mapNotNull { (value[it] as? JsonPrimitive)?.contentOrNull }
            .joinToString(" ")
            .lowercase()
        return when {
            id == "1007" || "case" in code || "案件" in code -> BusinessWorkbenchKind.CASE
            id == "1006" || "appointment" in code || "预约" in code -> BusinessWorkbenchKind.APPOINTMENT
            id == "1003" || "counselor" in code || "顾问" in code || "服务" in code -> BusinessWorkbenchKind.COUNSELOR_SERVICE
            id == "1004" || "visit" in code || "拜访" in code -> BusinessWorkbenchKind.VISIT
            else -> null
        }
    }

    private fun teamIds(snapshot: com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSnapshot): Set<String> {
        val values = when (val raw = snapshot.teams.data) {
            is JsonArray -> raw
            is JsonObject -> (raw["items"] as? JsonArray) ?: JsonArray(listOf(raw))
            else -> return emptySet()
        }
        return values.mapNotNull { item ->
            ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                ?: ((item as? JsonObject)?.get("teamId") as? JsonPrimitive)?.contentOrNull
        }.toSet()
    }

    private fun sectionIds(raw: kotlinx.serialization.json.JsonElement?): List<String> {
        val values = when (raw) {
            is JsonArray -> raw
            is JsonObject -> (raw["items"] as? JsonArray) ?: JsonArray(listOf(raw))
            else -> return emptyList()
        }
        return values.mapNotNull { item ->
            val value = item as? JsonObject ?: return@mapNotNull null
            if ((value["enabled"] as? JsonPrimitive)?.contentOrNull == "false") return@mapNotNull null
            ((value["id"] as? JsonPrimitive)?.contentOrNull)
                ?.takeIf(String::isNotBlank)
        }
    }
}
