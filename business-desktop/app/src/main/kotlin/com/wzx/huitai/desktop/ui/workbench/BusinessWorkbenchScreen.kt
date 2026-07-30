package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortKind
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchLoadState
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchState
import com.wzx.huitai.desktop.workbench.BusinessScheduleDraft
import com.wzx.huitai.desktop.workbench.BusinessScheduleFormOption
import com.wzx.huitai.desktop.workbench.BusinessScheduleFormState
import com.wzx.huitai.desktop.workbench.BusinessScheduleRelationType
import com.wzx.huitai.desktop.workbench.BusinessScheduleState
import com.wzx.huitai.desktop.workbench.BusinessScheduleViewMode
import com.wzx.huitai.desktop.workbench.BusinessAttachmentUploadState
import java.time.LocalDate

object WorkbenchTags {
    const val ROOT = "business-workbench-root"
    const val HEADER = "business-workbench-header"
    const val REFRESH = "business-workbench-refresh"
    const val NAVIGATION = "business-workbench-navigation"
    const val QUICK_ENTRANCES = "business-workbench-quick-entrances"
    const val QUICK_EMPTY = "business-workbench-quick-empty"
    const val STATISTICS = "business-workbench-statistics"
    const val STATISTICS_EMPTY = "business-workbench-statistics-empty"
    const val LIST = "business-workbench-list"
    const val LIST_LOADING = "business-workbench-list-loading"
    const val LIST_EMPTY = "business-workbench-list-empty"
    const val LIST_ERROR = "business-workbench-list-error"
    const val LIST_RETRY = "business-workbench-list-retry"
    const val PREVIOUS = "business-workbench-previous"
    const val NEXT = "business-workbench-next"
    const val QUICK_PREVIOUS = "business-workbench-quick-previous"
    const val QUICK_NEXT = "business-workbench-quick-next"
    const val PROFILE = "business-workbench-profile"
    const val SCOPE = "business-workbench-scope"

    fun navItem(path: String) = "business-workbench-nav-${path.replace('/', '_').ifBlank { "root" }}"
    fun quickItem(index: Int) = "business-workbench-quick-$index"
    fun statisticItem(index: Int) = "business-workbench-statistic-$index"
    fun listItem(id: String) = "business-workbench-list-$id"
    fun scopeItem(scope: String) = "business-workbench-scope-$scope"
}

@Composable
fun BusinessWorkbenchScreen(
    state: BusinessWorkbenchState,
    scheduleState: BusinessScheduleState = BusinessScheduleState(),
    scheduleFormState: BusinessScheduleFormState = BusinessScheduleFormState(),
    scheduleUploadState: BusinessAttachmentUploadState = BusinessAttachmentUploadState(),
    selectedPath: String = "/",
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    onNavigationSelected: (String) -> Unit = {},
    onQuickEntrance: (String) -> Unit = {},
    onStatisticSelected: (Int) -> Unit = {},
    onKindSelected: (BusinessWorkbenchKind) -> Unit = {},
    onScopeSelected: (com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope) -> Unit = {},
    onTeamSelected: (String) -> Unit = {},
    onRoleSelected: (String?) -> Unit = {},
    onSortRequested: (BusinessWorkbenchSortKind, List<String>) -> Unit = { _, _ -> },
    onRetryPage: () -> Unit = {},
    onPreviousPage: () -> Unit = {},
    onNextPage: () -> Unit = {},
    onCaseSelected: (String) -> Unit = {},
    onSchedulePrevious: () -> Unit = {},
    onScheduleNext: () -> Unit = {},
    onScheduleToday: () -> Unit = {},
    onScheduleViewModeChanged: (BusinessScheduleViewMode) -> Unit = {},
    onScheduleOnlyMineChanged: (Boolean) -> Unit = {},
    onScheduleDateSelected: (LocalDate) -> Unit = {},
    onScheduleCompletionChanged: (String, Boolean) -> Unit = { _, _ -> },
    onScheduleCreate: () -> Unit = {},
    onScheduleDraftChanged: (BusinessScheduleDraft) -> Unit = {},
    onScheduleRelationTypeSelected: (BusinessScheduleRelationType) -> Unit = {},
    onScheduleRelationOptionSelected: (BusinessScheduleRelationType, BusinessScheduleFormOption) -> Unit = { _, _ -> },
    onScheduleLoadRelationOptions: () -> Unit = {},
    onScheduleChooseAttachments: () -> Unit = {},
    onScheduleCancelUpload: () -> Unit = {},
    onScheduleRemoveAttachment: (String) -> Unit = {},
    onScheduleSubmit: () -> Unit = {},
    onScheduleDismiss: () -> Unit = {},
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(WorkbenchTags.ROOT)) {
      Column(Modifier.fillMaxSize()) {
        WorkbenchHeader(
            notice = state.error ?: state.snapshot?.issues?.firstOrNull(),
            onRefresh = onRefresh,
            modifier = Modifier,
        )
        Row(Modifier.fillMaxSize()) {
            WorkbenchNavigation(
                items = state.navigation,
                selectedPath = selectedPath,
                onSelected = { onNavigationSelected(it.path) },
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.loadState) {
                    BusinessWorkbenchLoadState.LOADING -> Text("工作台加载中…", modifier = Modifier.testTag("business-workbench-loading"))
                    BusinessWorkbenchLoadState.ERROR -> Text(state.error ?: "工作台加载失败", modifier = Modifier.testTag("business-workbench-error"))
                    else -> Unit
                }
                QuickEntranceCard(
                    data = state.snapshot?.shortcuts?.data,
                    onOpen = onQuickEntrance,
                    order = state.shortcutOrder,
                    onSortChange = { onSortRequested(BusinessWorkbenchSortKind.SHORTCUT, it) },
                )
                DataStatisticsCard(
                    data = state.snapshot?.summary?.data,
                    selectedIndex = state.selectedStatistic,
                    onSelected = onStatisticSelected,
                    order = state.summaryOrder,
                    onSortChange = { onSortRequested(BusinessWorkbenchSortKind.SUMMARY, it) },
                )
                state.sortError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.76f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DataScopeSelector(
                            scope = state.scope,
                            onScopeSelected = onScopeSelected,
                            teams = state.snapshot?.teams?.data,
                            teamId = state.teamId,
                            roles = state.roles,
                            roleCode = state.roleCode,
                            onTeamSelected = onTeamSelected,
                            onRoleSelected = onRoleSelected,
                        )
                        KindSelector(state.selectedKind, onKindSelected)
                        BusinessListCard(state.page, state.pageLoading, state.pageError, onRetryPage, onPreviousPage, onNextPage, onCaseSelected, state.selectedKind)
                    }
                    Column(Modifier.weight(0.24f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        WorkbenchProfileCard(state.snapshot?.profile?.data)
                        SchedulePanel(
                            state = scheduleState,
                            onPrevious = onSchedulePrevious,
                            onNext = onScheduleNext,
                            onToday = onScheduleToday,
                            onViewModeChanged = onScheduleViewModeChanged,
                            onOnlyMineChanged = onScheduleOnlyMineChanged,
                            onDateSelected = onScheduleDateSelected,
                            onCompletionChanged = onScheduleCompletionChanged,
                            onCreate = onScheduleCreate,
                            modifier = Modifier.fillMaxWidth().height(700.dp),
                        )
                    }
                }
            }
        }
      }
      ScheduleCreateDialog(
          state = scheduleFormState,
          uploadState = scheduleUploadState,
          onDraftChanged = onScheduleDraftChanged,
          onRelationTypeSelected = onScheduleRelationTypeSelected,
          onRelationOptionSelected = onScheduleRelationOptionSelected,
          onLoadRelationOptions = onScheduleLoadRelationOptions,
          onChooseAttachments = onScheduleChooseAttachments,
          onCancelUpload = onScheduleCancelUpload,
          onRemoveAttachment = onScheduleRemoveAttachment,
          onSubmit = onScheduleSubmit,
          onDismiss = onScheduleDismiss,
          modifier = Modifier.fillMaxSize(),
      )
    }
}

@Composable
private fun KindSelector(selected: BusinessWorkbenchKind, onSelected: (BusinessWorkbenchKind) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("business-workbench-kinds")) {
        BusinessWorkbenchKind.entries.forEach { kind ->
            androidx.compose.material3.FilterChip(
                selected = kind == selected,
                onClick = { onSelected(kind) },
                label = { Text(kind.title()) },
                modifier = Modifier.testTag("business-workbench-kind-${kind.name}"),
            )
        }
    }
}

private fun BusinessWorkbenchKind.title() = when (this) {
    BusinessWorkbenchKind.CASE -> "案件"
    BusinessWorkbenchKind.APPOINTMENT -> "预约"
    BusinessWorkbenchKind.COUNSELOR_SERVICE -> "顾问服务"
    BusinessWorkbenchKind.VISIT -> "拜访"
}
