package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchClient
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPageRequest
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Orchestrates BFF calls; composables only receive state and intents. */
class BusinessWorkbenchController(
    private val client: BusinessWorkbenchClient,
    private val reducer: BusinessWorkbenchReducer = BusinessWorkbenchReducer(),
) {
    private val mutableState = MutableStateFlow(BusinessWorkbenchState())
    private var nextRequestId = 0L
    private var activeEpoch: Long? = null
    val state: StateFlow<BusinessWorkbenchState> = mutableState.asStateFlow()

    suspend fun load(identityEpoch: Long) {
        activeEpoch = identityEpoch
        val requestId = ++nextRequestId
        dispatch(BusinessWorkbenchEvent.LoadStarted(requestId, identityEpoch))
        runCatching {
            coroutineScope {
                val snapshot = async { client.get() }
                val navigation = async { client.navigation() }
                snapshot.await() to navigation.await()
            }
        }.onSuccess { (snapshot, navigation) ->
            if (snapshot.identityEpoch != identityEpoch ||
                navigation.identityEpoch != identityEpoch ||
                snapshot.generation != navigation.generation
            ) {
                dispatch(BusinessWorkbenchEvent.LoadFailed(requestId, identityEpoch, "工作台数据版本不一致，请重试"))
            } else if (activeEpoch == identityEpoch) {
                dispatch(BusinessWorkbenchEvent.Loaded(requestId, snapshot, navigation.items))
            }
        }.onFailure { failure ->
            if (activeEpoch == identityEpoch) dispatch(BusinessWorkbenchEvent.LoadFailed(requestId, identityEpoch, safeMessage(failure)))
        }
    }

    suspend fun loadPage() {
        val current = state.value
        val epoch = current.identityEpoch ?: return
        val requestId = ++nextRequestId
        dispatch(BusinessWorkbenchEvent.PageStarted(requestId, epoch))
        runCatching {
            client.page(
                BusinessWorkbenchPageRequest(
                    kind = current.selectedKind,
                    scope = current.scope,
                    teamId = current.teamId,
                    roleCode = current.roleCode,
                    pageNo = current.pageNo,
                    pageSize = current.pageSize,
                ),
            )
        }.onSuccess { page ->
            if (activeEpoch == epoch) dispatch(BusinessWorkbenchEvent.PageLoaded(requestId, epoch, page))
        }.onFailure { failure ->
            if (activeEpoch == epoch) dispatch(BusinessWorkbenchEvent.PageFailed(requestId, epoch, safeMessage(failure)))
        }
    }

    fun selectKind(kind: com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind) =
        dispatch(BusinessWorkbenchEvent.KindChanged(kind))

    fun selectScope(scope: BusinessWorkbenchScope, teamId: String? = null, roleCode: String? = null) =
        dispatch(BusinessWorkbenchEvent.ScopeChanged(scope, teamId, roleCode))

    fun selectStatistic(index: Int) = dispatch(BusinessWorkbenchEvent.StatisticSelected(index))

    suspend fun changeStatistic(index: Int) {
        val kind = statisticKind(state.value.snapshot, index) ?: return
        dispatch(BusinessWorkbenchEvent.StatisticSelected(index))
        dispatch(BusinessWorkbenchEvent.KindChanged(kind))
        reloadRolesIfNeeded()
        loadPage()
    }

    suspend fun changeKind(kind: BusinessWorkbenchKind) {
        dispatch(BusinessWorkbenchEvent.KindChanged(kind))
        reloadRolesIfNeeded()
        loadPage()
    }

    suspend fun changeScope(scope: BusinessWorkbenchScope) {
        if (scope != BusinessWorkbenchScope.TEAM) {
            dispatch(BusinessWorkbenchEvent.ScopeChanged(scope, null, null))
            loadPage()
            return
        }
        val teamId = authorizedTeamIds(state.value.snapshot).firstOrNull()
        if (teamId == null) {
            dispatch(BusinessWorkbenchEvent.ScopeChanged(BusinessWorkbenchScope.ALL, null, null))
            loadPage()
            return
        }
        dispatch(BusinessWorkbenchEvent.ScopeChanged(BusinessWorkbenchScope.TEAM, teamId, null))
        loadRoles(teamId)
        loadPage()
    }

    suspend fun changeTeam(teamId: String) {
        if (teamId !in authorizedTeamIds(state.value.snapshot)) {
            dispatch(BusinessWorkbenchEvent.ScopeChanged(BusinessWorkbenchScope.ALL, null, null))
            loadPage()
            return
        }
        dispatch(BusinessWorkbenchEvent.ScopeChanged(BusinessWorkbenchScope.TEAM, teamId, null))
        loadRoles(teamId)
        loadPage()
    }

    suspend fun changeRole(roleCode: String?) {
        val current = state.value
        if (current.scope != BusinessWorkbenchScope.TEAM || current.teamId == null) return
        val safeRole = roleCode?.takeIf { code -> current.roles.any { it.roleCode == code } }
        dispatch(BusinessWorkbenchEvent.ScopeChanged(BusinessWorkbenchScope.TEAM, current.teamId, safeRole))
        loadPage()
    }

    suspend fun updateSort(kind: BusinessWorkbenchSortKind, ids: List<String>) {
        val current = state.value
        if (current.sortLoading) return
        val epoch = current.identityEpoch ?: return
        val generation = current.generation ?: return
        val rollback = when (kind) {
            BusinessWorkbenchSortKind.SHORTCUT -> current.shortcutOrder
            BusinessWorkbenchSortKind.SUMMARY -> current.summaryOrder
        }
        val expectedRevision = when (kind) {
            BusinessWorkbenchSortKind.SHORTCUT -> current.shortcutSortRevision
            BusinessWorkbenchSortKind.SUMMARY -> current.summarySortRevision
        }
        val request = BusinessWorkbenchSortRequest(kind, ids, expectedRevision)
        val requestId = ++nextRequestId
        dispatch(
            BusinessWorkbenchEvent.SortStarted(
                requestId = requestId,
                identityEpoch = epoch,
                generation = generation,
                kind = kind,
                optimisticIds = request.ids,
            ),
        )
        runCatching { client.updateSort(request) }
            .onSuccess { result ->
                if (activeEpoch != epoch || result.identityEpoch != epoch || result.generation != generation) {
                    dispatch(
                        BusinessWorkbenchEvent.SortFailed(
                            requestId = requestId,
                            identityEpoch = epoch,
                            generation = generation,
                            kind = kind,
                            rollbackIds = rollback,
                            message = "排序结果已过期，请重试",
                        ),
                    )
                    return@onSuccess
                }
                if (result.refreshRequired) {
                    runCatching { refreshAfterSort(epoch, generation) }
                        .onSuccess {
                            val canonical = when (kind) {
                                BusinessWorkbenchSortKind.SHORTCUT -> state.value.shortcutOrder
                                BusinessWorkbenchSortKind.SUMMARY -> state.value.summaryOrder
                            }
                            dispatch(
                                BusinessWorkbenchEvent.SortSucceeded(
                                    requestId = requestId,
                                    identityEpoch = epoch,
                                    generation = generation,
                                    kind = kind,
                                    revision = result.revision,
                                    canonicalIds = canonical,
                                ),
                            )
                            loadPage()
                        }
                        .onFailure { failure ->
                            dispatch(
                                BusinessWorkbenchEvent.SortFailed(
                                    requestId = requestId,
                                    identityEpoch = epoch,
                                    generation = generation,
                                    kind = kind,
                                    rollbackIds = rollback,
                                    message = "排序已提交，但刷新失败：${safeMessage(failure)}",
                                    acceptedRevision = result.revision,
                                ),
                            )
                        }
                } else {
                    dispatch(
                        BusinessWorkbenchEvent.SortSucceeded(
                            requestId = requestId,
                            identityEpoch = epoch,
                            generation = generation,
                            kind = kind,
                            revision = result.revision,
                            canonicalIds = requireNotNull(result.canonicalIds),
                        ),
                    )
                }
            }
            .onFailure { failure ->
                dispatch(
                    BusinessWorkbenchEvent.SortFailed(
                        requestId = requestId,
                        identityEpoch = epoch,
                        generation = generation,
                        kind = kind,
                        rollbackIds = rollback,
                        message = safeMessage(failure),
                    ),
                )
            }
    }

    fun nextPage() {
        val current = state.value
        if (current.page != null && current.pageNo * current.pageSize < current.page.total) {
            mutableState.value = current.copy(pageNo = current.pageNo + 1, page = null)
        }
    }

    fun previousPage() {
        val current = state.value
        if (current.pageNo > 1) mutableState.value = current.copy(pageNo = current.pageNo - 1, page = null)
    }

    fun clear() {
        activeEpoch = null
        ++nextRequestId
        dispatch(BusinessWorkbenchEvent.Cleared)
    }

    private fun dispatch(event: BusinessWorkbenchEvent) {
        mutableState.value = reducer.reduce(mutableState.value, event)
    }

    private suspend fun reloadRolesIfNeeded() {
        val current = state.value
        if (current.scope == BusinessWorkbenchScope.TEAM) {
            val teamId = current.teamId
            if (teamId == null || teamId !in authorizedTeamIds(current.snapshot)) {
                dispatch(BusinessWorkbenchEvent.ScopeChanged(BusinessWorkbenchScope.ALL, null, null))
            } else {
                loadRoles(teamId)
            }
        }
    }

    private suspend fun loadRoles(teamId: String) {
        val current = state.value
        val epoch = current.identityEpoch ?: return
        val generation = current.generation ?: return
        val kind = current.selectedKind
        val requestId = ++nextRequestId
        dispatch(BusinessWorkbenchEvent.RolesStarted(requestId, epoch))
        runCatching { client.teamRoles(teamId, kind) }
            .onSuccess { result ->
                if (activeEpoch == epoch && result.identityEpoch == epoch && result.generation == generation) {
                    dispatch(
                        BusinessWorkbenchEvent.RolesLoaded(
                            requestId,
                            epoch,
                            generation,
                            teamId,
                            kind,
                            result.items,
                        ),
                    )
                } else {
                    dispatch(BusinessWorkbenchEvent.RolesFailed(requestId, epoch, "团队角色结果已过期，请重试"))
                }
            }
            .onFailure { failure ->
                dispatch(BusinessWorkbenchEvent.RolesFailed(requestId, epoch, safeMessage(failure)))
            }
    }

    private suspend fun refreshAfterSort(epoch: Long, generation: Long) {
        coroutineScope {
            val snapshotDeferred = async { client.get() }
            val navigationDeferred = async { client.navigation() }
            val snapshot = snapshotDeferred.await()
            val navigation = navigationDeferred.await()
            if (activeEpoch != epoch ||
                snapshot.identityEpoch != epoch ||
                navigation.identityEpoch != epoch ||
                snapshot.generation != generation ||
                navigation.generation != generation
            ) {
                throw IllegalStateException("工作台刷新结果已过期")
            }
            val requestId = ++nextRequestId
            dispatch(BusinessWorkbenchEvent.LoadStarted(requestId, epoch))
            dispatch(BusinessWorkbenchEvent.Loaded(requestId, snapshot, navigation.items))
        }
    }

    private fun authorizedTeamIds(
        snapshot: com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSnapshot?,
    ): List<String> {
        val raw = snapshot?.teams?.data
        val values = when (raw) {
            is JsonArray -> raw
            is JsonObject -> (raw["items"] as? JsonArray) ?: JsonArray(listOf(raw))
            else -> return emptyList()
        }
        return values.mapNotNull { item ->
            val value = item as? JsonObject ?: return@mapNotNull null
            ((value["id"] as? JsonPrimitive)?.contentOrNull
                ?: (value["teamId"] as? JsonPrimitive)?.contentOrNull)
                ?.takeIf(String::isNotBlank)
        }.distinct()
    }

    private fun statisticKind(
        snapshot: com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSnapshot?,
        index: Int,
    ): BusinessWorkbenchKind? {
        val raw = snapshot?.summary?.data
        val values = when (raw) {
            is JsonArray -> raw
            is JsonObject -> JsonArray(listOf(raw))
            else -> return null
        }
        val value = values.getOrNull(index) as? JsonObject ?: return null
        if ((value["enabled"] as? JsonPrimitive)?.contentOrNull == "false") return null
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

    private fun safeMessage(failure: Throwable): String = failure.message?.takeIf { it.isNotBlank() } ?: "工作台加载失败，请重试"
}
