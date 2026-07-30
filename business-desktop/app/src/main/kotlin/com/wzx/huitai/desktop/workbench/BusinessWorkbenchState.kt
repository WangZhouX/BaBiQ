package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.agent.business.auth.BusinessNavigationTarget
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPage
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSnapshot
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchTeamRole

enum class BusinessWorkbenchLoadState { IDLE, LOADING, READY, ERROR }

data class BusinessWorkbenchState(
    val loadState: BusinessWorkbenchLoadState = BusinessWorkbenchLoadState.IDLE,
    val identityEpoch: Long? = null,
    val generation: Long? = null,
    val snapshot: BusinessWorkbenchSnapshot? = null,
    val navigation: List<BusinessNavigationTarget> = emptyList(),
    val error: String? = null,
    val page: BusinessWorkbenchPage? = null,
    val pageLoading: Boolean = false,
    val pageError: String? = null,
    val selectedKind: BusinessWorkbenchKind = BusinessWorkbenchKind.CASE,
    val scope: BusinessWorkbenchScope = BusinessWorkbenchScope.ALL,
    val teamId: String? = null,
    val roleCode: String? = null,
    val pageNo: Int = 1,
    val pageSize: Int = 20,
    val selectedStatistic: Int = 0,
    val roles: List<BusinessWorkbenchTeamRole> = emptyList(),
    val rolesLoading: Boolean = false,
    val rolesError: String? = null,
    val shortcutOrder: List<String> = emptyList(),
    val summaryOrder: List<String> = emptyList(),
    val shortcutSortRevision: Long = 0,
    val summarySortRevision: Long = 0,
    val sortLoading: Boolean = false,
    val sortError: String? = null,
    internal val loadRequestId: Long = 0,
    internal val pageRequestId: Long = 0,
    internal val rolesRequestId: Long = 0,
    internal val sortRequestId: Long = 0,
)

sealed interface BusinessWorkbenchEvent {
    data class LoadStarted(val requestId: Long, val identityEpoch: Long) : BusinessWorkbenchEvent
    data class Loaded(
        val requestId: Long,
        val snapshot: BusinessWorkbenchSnapshot,
        val navigation: List<BusinessNavigationTarget>,
    ) : BusinessWorkbenchEvent
    data class LoadFailed(val requestId: Long, val identityEpoch: Long, val message: String) : BusinessWorkbenchEvent
    data class PageStarted(val requestId: Long, val identityEpoch: Long) : BusinessWorkbenchEvent
    data class PageLoaded(val requestId: Long, val identityEpoch: Long, val page: BusinessWorkbenchPage) : BusinessWorkbenchEvent
    data class PageFailed(val requestId: Long, val identityEpoch: Long, val message: String) : BusinessWorkbenchEvent
    data class EpochChanged(val identityEpoch: Long) : BusinessWorkbenchEvent
    data class KindChanged(val kind: BusinessWorkbenchKind) : BusinessWorkbenchEvent
    data class ScopeChanged(val scope: BusinessWorkbenchScope, val teamId: String?, val roleCode: String?) : BusinessWorkbenchEvent
    data class StatisticSelected(val index: Int) : BusinessWorkbenchEvent
    data class RolesStarted(val requestId: Long, val identityEpoch: Long) : BusinessWorkbenchEvent
    data class RolesLoaded(
        val requestId: Long,
        val identityEpoch: Long,
        val generation: Long,
        val teamId: String,
        val kind: BusinessWorkbenchKind,
        val roles: List<BusinessWorkbenchTeamRole>,
    ) : BusinessWorkbenchEvent
    data class RolesFailed(val requestId: Long, val identityEpoch: Long, val message: String) : BusinessWorkbenchEvent
    data class SortStarted(
        val requestId: Long,
        val identityEpoch: Long,
        val generation: Long,
        val kind: BusinessWorkbenchSortKind,
        val optimisticIds: List<String>,
    ) : BusinessWorkbenchEvent
    data class SortSucceeded(
        val requestId: Long,
        val identityEpoch: Long,
        val generation: Long,
        val kind: BusinessWorkbenchSortKind,
        val revision: Long,
        val canonicalIds: List<String>,
    ) : BusinessWorkbenchEvent
    data class SortFailed(
        val requestId: Long,
        val identityEpoch: Long,
        val generation: Long,
        val kind: BusinessWorkbenchSortKind,
        val rollbackIds: List<String>,
        val message: String,
        val acceptedRevision: Long? = null,
    ) : BusinessWorkbenchEvent
    data object Cleared : BusinessWorkbenchEvent
}
