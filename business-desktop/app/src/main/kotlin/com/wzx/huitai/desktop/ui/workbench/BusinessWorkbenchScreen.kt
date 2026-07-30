package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortKind
import com.wzx.huitai.desktop.workbench.BusinessAttachmentUploadState
import com.wzx.huitai.desktop.workbench.BusinessScheduleDraft
import com.wzx.huitai.desktop.workbench.BusinessScheduleFormOption
import com.wzx.huitai.desktop.workbench.BusinessScheduleFormState
import com.wzx.huitai.desktop.workbench.BusinessScheduleRelationType
import com.wzx.huitai.desktop.workbench.BusinessScheduleState
import com.wzx.huitai.desktop.workbench.BusinessScheduleViewMode
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchLoadState
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchState
import java.time.LocalDate

object WorkbenchTags {
    const val ROOT = "business-workbench-root"
    const val HEADER = "business-workbench-header"
    const val REFRESH = "business-workbench-refresh"
    const val NAVIGATION = "business-workbench-navigation"
    const val LEFT_COLUMN = "business-workbench-left-column"
    const val RIGHT_COLUMN = "business-workbench-right-column"
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

/**
 * 工作台页面按照 Web 的固定双栏骨架渲染。
 *
 * 页面本身不再整体滚动：左侧案件列表和右侧日程各自消费剩余高度，保证快捷入口、
 * 统计卡片和用户资料在窗口内始终保持与 Web 一致的位置。
 */
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
    showHeader: Boolean = true,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(BusinessWorkbenchVisualSpec.pageBackground)
            .padding(BusinessWorkbenchVisualSpec.pagePadding)
            .testTag(WorkbenchTags.ROOT),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (showHeader) {
                Box(Modifier.fillMaxWidth().height(0.dp).testTag(WorkbenchTags.HEADER))
            }
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(BusinessWorkbenchVisualSpec.columnGap),
            ) {
            Column(
                Modifier
                    .weight(BusinessWorkbenchVisualSpec.leftColumnWeight)
                    .fillMaxHeight()
                    .testTag(WorkbenchTags.LEFT_COLUMN),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (state.loadState) {
                    BusinessWorkbenchLoadState.LOADING ->
                        Text("工作台加载中…", modifier = Modifier.testTag("business-workbench-loading"))
                    BusinessWorkbenchLoadState.ERROR ->
                        Text(state.error ?: "工作台加载失败", modifier = Modifier.testTag("business-workbench-error"))
                    else -> Unit
                }
                QuickEntranceCard(
                    data = state.snapshot?.shortcuts?.data,
                    onOpen = onQuickEntrance,
                    order = state.shortcutOrder,
                    onSortChange = { onSortRequested(BusinessWorkbenchSortKind.SHORTCUT, it) },
                    modifier = Modifier.fillMaxWidth().height(BusinessWorkbenchVisualSpec.quickEntranceHeight),
                )
                Column(
                    Modifier.fillMaxWidth().weight(1f).background(BusinessWorkbenchVisualSpec.surface),
                ) {
                    DataStatisticsCard(
                        data = state.snapshot?.summary?.data,
                        selectedIndex = state.selectedStatistic,
                        onSelected = onStatisticSelected,
                        order = state.summaryOrder,
                        onSortChange = { onSortRequested(BusinessWorkbenchSortKind.SUMMARY, it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.sortError?.let {
                        Text(
                            it,
                            color = BusinessWorkbenchVisualSpec.danger,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    BusinessListFilters(
                        state = state,
                        onScopeSelected = onScopeSelected,
                        onTeamSelected = onTeamSelected,
                        onRoleSelected = onRoleSelected,
                        onKindSelected = onKindSelected,
                    )
                    BusinessListCard(
                        page = state.page,
                        loading = state.pageLoading,
                        error = state.pageError,
                        onRetry = onRetryPage,
                        onPrevious = onPreviousPage,
                        onNext = onNextPage,
                        onCaseSelected = onCaseSelected,
                        kind = state.selectedKind,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
            Column(
                Modifier
                    .weight(BusinessWorkbenchVisualSpec.rightColumnWeight)
                    .fillMaxHeight()
                    .testTag(WorkbenchTags.RIGHT_COLUMN),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WorkbenchProfileCard(
                    data = state.snapshot?.profile?.data,
                    modifier = Modifier.fillMaxWidth().height(BusinessWorkbenchVisualSpec.profileHeight),
                )
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
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
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

/** Web 列表头把数据范围和当前业务状态放在同一行。 */
@Composable
private fun BusinessListFilters(
    state: BusinessWorkbenchState,
    onScopeSelected: (com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope) -> Unit,
    onTeamSelected: (String) -> Unit,
    onRoleSelected: (String?) -> Unit,
    onKindSelected: (BusinessWorkbenchKind) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BusinessWorkbenchVisualSpec.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DataScopeSelector(
            scope = state.scope,
            onScopeSelected = onScopeSelected,
            teams = state.snapshot?.teams?.data,
            teamId = state.teamId,
            roles = state.roles,
            roleCode = state.roleCode,
            onTeamSelected = onTeamSelected,
            onRoleSelected = onRoleSelected,
            modifier = Modifier.weight(1f),
        )
        KindSelector(state.selectedKind, onKindSelected)
    }
}

/** 业务类型是工作台统计卡的辅助入口，采用 Web 的轻量文字 Tab。 */
@Composable
private fun KindSelector(selected: BusinessWorkbenchKind, onSelected: (BusinessWorkbenchKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("business-workbench-kinds")) {
        BusinessWorkbenchKind.entries.forEach { kind ->
            Text(
                text = kind.title(),
                color = if (kind == selected) BusinessWorkbenchVisualSpec.primary else BusinessWorkbenchVisualSpec.textSecondary,
                modifier = Modifier
                    .testTag("business-workbench-kind-${kind.name}")
                    .clickable { onSelected(kind) }
                    .padding(vertical = 7.dp),
            )
        }
    }
}

private fun BusinessWorkbenchKind.title() = when (this) {
    BusinessWorkbenchKind.CASE -> "全部"
    BusinessWorkbenchKind.APPOINTMENT -> "预约"
    BusinessWorkbenchKind.COUNSELOR_SERVICE -> "顾问服务"
    BusinessWorkbenchKind.VISIT -> "拜访"
}
