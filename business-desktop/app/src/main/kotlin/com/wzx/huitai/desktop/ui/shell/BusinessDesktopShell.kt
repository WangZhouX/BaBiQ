package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessProviderDraft
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsState
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessFieldSuggestion
import com.wzx.huitai.desktop.ui.agent.BusinessAgentPanel
import com.wzx.huitai.desktop.ui.agent.BusinessAgentCollapsedRail
import com.wzx.huitai.desktop.ui.form.DemoFormPanel
import com.wzx.huitai.desktop.ui.layout.BusinessDesktopLayoutMode
import com.wzx.huitai.desktop.ui.layout.BusinessDesktopLayoutPolicy
import com.wzx.huitai.desktop.ui.settings.BusinessProviderSettingsPanel

/** 跨组件共享的稳定语义标签，供桌面 UI 自动化定位而不依赖视觉文本。 */
object BusinessUiTags {
    const val SIDEBAR = "business-sidebar"
    const val FORM_PANEL = "business-form-panel"
    const val AGENT_PANEL = "business-agent-panel"
    const val AGENT_COLLAPSED_RAIL = "business-agent-collapsed-rail"
    const val PLACEHOLDER_PANEL = "business-placeholder-panel"
}

/**
 * 按唯一 canonical destination 装配导航、中心工作区和 Agent 面板。
 *
 * 响应式切换只改变视觉映射；[selectedDestination] 永远由上层持有，Shell 不复制导航状态。
 */
@Composable
fun BusinessDesktopShell(
    state: BusinessDesktopState,
    formState: DemoFormState,
    providerSettingsState: BusinessProviderSettingsState = BusinessProviderSettingsState(),
    selectedDestination: BusinessDesktopDestination = BusinessDesktopDestination.DATA_ENTRY,
    selectedModelId: String? = null,
    composerText: String = "",
    agentPanelExpanded: Boolean = true,
    onDestinationSelected: (BusinessDesktopDestination) -> Unit = {},
    onFieldEdited: (fieldId: String, value: String) -> Unit = { _, _ -> },
    onSuggestionsChanged: (Map<String, BusinessFieldSuggestion>) -> Unit = {},
    onAcceptSuggestion: (fieldId: String, baseRevision: Long) -> Unit = { _, _ -> },
    onAcceptAllSuggestions: (baseRevision: Long) -> Unit = {},
    onSaveDraft: () -> Unit = {},
    onSubmit: () -> Unit = {},
    onComposerTextChanged: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onReconnect: () -> Unit = {},
    onProviderSelected: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    onProviderRefresh: () -> Unit = {},
    onProviderCreate: suspend (BusinessProviderDraft) -> Boolean = { false },
    onProviderUpdate: suspend (BusinessProviderDraft) -> Boolean = { false },
    onProviderDelete: (String) -> Unit = {},
    onProviderTest: (String) -> Unit = {},
    onProviderActivated: (providerId: String, modelId: String?) -> Unit = { _, _ -> },
    onProviderOAuthStatus: (String) -> Unit = {},
    onProviderOAuthLogin: (String) -> Unit = {},
    onAgentPanelExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val layout = BusinessDesktopLayoutPolicy.resolve(maxWidth, agentPanelExpanded)
        if (layout.mode == BusinessDesktopLayoutMode.COMPACT) {
            val visualDestination = selectedDestination.compactVisualDestination()
            Column(Modifier.fillMaxSize()) {
                PrimaryTabRow(
                    selectedTabIndex = when (visualDestination) {
                        BusinessDesktopDestination.DATA_ENTRY -> 0
                        BusinessDesktopDestination.SETTINGS -> 1
                        BusinessDesktopDestination.AGENT -> 2
                        else -> 0
                    },
                    modifier = Modifier.height(48.dp),
                ) {
                    CompactDestinationTab(
                        destination = BusinessDesktopDestination.DATA_ENTRY,
                        selected = visualDestination == BusinessDesktopDestination.DATA_ENTRY,
                        onDestinationSelected = onDestinationSelected,
                    )
                    CompactDestinationTab(
                        destination = BusinessDesktopDestination.SETTINGS,
                        selected = visualDestination == BusinessDesktopDestination.SETTINGS,
                        onDestinationSelected = onDestinationSelected,
                    )
                    CompactDestinationTab(
                        destination = BusinessDesktopDestination.AGENT,
                        selected = visualDestination == BusinessDesktopDestination.AGENT,
                        onDestinationSelected = onDestinationSelected,
                    )
                }
                when (visualDestination) {
                    BusinessDesktopDestination.SETTINGS -> ProviderSettingsForShell(
                        state = providerSettingsState,
                        onRefresh = onProviderRefresh,
                        onCreate = onProviderCreate,
                        onUpdate = onProviderUpdate,
                        onDelete = onProviderDelete,
                        onTest = onProviderTest,
                        onSetActive = onProviderActivated,
                        onOAuthStatus = onProviderOAuthStatus,
                        onOAuthLogin = onProviderOAuthLogin,
                        modifier = Modifier.weight(1f),
                    )
                    BusinessDesktopDestination.AGENT -> AgentPanelForShell(
                        state = state,
                        formState = formState,
                        selectedModelId = selectedModelId,
                        composerText = composerText,
                        onComposerTextChanged = onComposerTextChanged,
                        onSend = onSend,
                        onReconnect = onReconnect,
                        onProviderSelected = onProviderSelected,
                        modifier = Modifier.weight(1f),
                    )
                    else -> FormPanelForShell(
                        state = state,
                        formState = formState,
                        onFieldEdited = onFieldEdited,
                        onSuggestionsChanged = onSuggestionsChanged,
                        onAcceptSuggestion = onAcceptSuggestion,
                        onAcceptAllSuggestions = onAcceptAllSuggestions,
                        onSaveDraft = onSaveDraft,
                        onSubmit = onSubmit,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                BusinessSidebar(
                    selected = selectedDestination,
                    compact = layout.mode == BusinessDesktopLayoutMode.MEDIUM,
                    onSelected = onDestinationSelected,
                    modifier = Modifier.width(layout.navigationWidth),
                )
                CenterPanelForShell(
                    destination = selectedDestination.wideCenterDestination(),
                    state = state,
                    formState = formState,
                    providerSettingsState = providerSettingsState,
                    onFieldEdited = onFieldEdited,
                    onSuggestionsChanged = onSuggestionsChanged,
                    onAcceptSuggestion = onAcceptSuggestion,
                    onAcceptAllSuggestions = onAcceptAllSuggestions,
                    onSaveDraft = onSaveDraft,
                    onSubmit = onSubmit,
                    onProviderRefresh = onProviderRefresh,
                    onProviderCreate = onProviderCreate,
                    onProviderUpdate = onProviderUpdate,
                    onProviderDelete = onProviderDelete,
                    onProviderTest = onProviderTest,
                    onProviderActivated = onProviderActivated,
                    onProviderOAuthStatus = onProviderOAuthStatus,
                    onProviderOAuthLogin = onProviderOAuthLogin,
                    modifier = Modifier.width(layout.formWidth),
                )
                if (agentPanelExpanded) {
                    AgentPanelForShell(
                        state = state,
                        formState = formState,
                        selectedModelId = selectedModelId,
                        composerText = composerText,
                        onComposerTextChanged = onComposerTextChanged,
                        onSend = onSend,
                        onReconnect = onReconnect,
                        onProviderSelected = onProviderSelected,
                        onCollapse = { onAgentPanelExpandedChange(false) },
                        modifier = Modifier.width(layout.agentWidth),
                    )
                } else {
                    BusinessAgentCollapsedRail(
                        onExpand = { onAgentPanelExpandedChange(true) },
                        modifier = Modifier.width(layout.agentWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactDestinationTab(
    destination: BusinessDesktopDestination,
    selected: Boolean,
    onDestinationSelected: (BusinessDesktopDestination) -> Unit,
) {
    Tab(
        selected = selected,
        onClick = { onDestinationSelected(destination) },
        text = { Text(destination.label) },
    )
}

@Composable
private fun CenterPanelForShell(
    destination: BusinessDesktopDestination,
    state: BusinessDesktopState,
    formState: DemoFormState,
    providerSettingsState: BusinessProviderSettingsState,
    onFieldEdited: (String, String) -> Unit,
    onSuggestionsChanged: (Map<String, BusinessFieldSuggestion>) -> Unit,
    onAcceptSuggestion: (String, Long) -> Unit,
    onAcceptAllSuggestions: (Long) -> Unit,
    onSaveDraft: () -> Unit,
    onSubmit: () -> Unit,
    onProviderRefresh: () -> Unit,
    onProviderCreate: suspend (BusinessProviderDraft) -> Boolean,
    onProviderUpdate: suspend (BusinessProviderDraft) -> Boolean,
    onProviderDelete: (String) -> Unit,
    onProviderTest: (String) -> Unit,
    onProviderActivated: (String, String?) -> Unit,
    onProviderOAuthStatus: (String) -> Unit,
    onProviderOAuthLogin: (String) -> Unit,
    modifier: Modifier,
) {
    when (destination) {
        BusinessDesktopDestination.DATA_ENTRY -> FormPanelForShell(
            state,
            formState,
            onFieldEdited,
            onSuggestionsChanged,
            onAcceptSuggestion,
            onAcceptAllSuggestions,
            onSaveDraft,
            onSubmit,
            modifier,
        )
        BusinessDesktopDestination.SETTINGS -> ProviderSettingsForShell(
            providerSettingsState,
            onProviderRefresh,
            onProviderCreate,
            onProviderUpdate,
            onProviderDelete,
            onProviderTest,
            onProviderActivated,
            onProviderOAuthStatus,
            onProviderOAuthLogin,
            modifier,
        )
        BusinessDesktopDestination.WORKBENCH -> PlaceholderPanel("工作台功能将在后续阶段开放", modifier)
        BusinessDesktopDestination.RUN_HISTORY -> PlaceholderPanel("运行记录功能将在后续阶段开放", modifier)
        BusinessDesktopDestination.AGENT -> Unit
    }
}

@Composable
private fun FormPanelForShell(
    state: BusinessDesktopState,
    formState: DemoFormState,
    onFieldEdited: (String, String) -> Unit,
    onSuggestionsChanged: (Map<String, BusinessFieldSuggestion>) -> Unit,
    onAcceptSuggestion: (String, Long) -> Unit,
    onAcceptAllSuggestions: (Long) -> Unit,
    onSaveDraft: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier,
) {
    DemoFormPanel(
        state = formState,
        suggestions = state.suggestions,
        onFieldEdited = onFieldEdited,
        onSuggestionsChanged = onSuggestionsChanged,
        onAcceptSuggestion = onAcceptSuggestion,
        onAcceptAllSuggestions = onAcceptAllSuggestions,
        onSaveDraft = onSaveDraft,
        onSubmit = onSubmit,
        modifier = modifier,
    )
}

@Composable
private fun ProviderSettingsForShell(
    state: BusinessProviderSettingsState,
    onRefresh: () -> Unit,
    onCreate: suspend (BusinessProviderDraft) -> Boolean,
    onUpdate: suspend (BusinessProviderDraft) -> Boolean,
    onDelete: (String) -> Unit,
    onTest: (String) -> Unit,
    onSetActive: (String, String?) -> Unit,
    onOAuthStatus: (String) -> Unit,
    onOAuthLogin: (String) -> Unit,
    modifier: Modifier,
) {
    BusinessProviderSettingsPanel(
        state = state,
        onRefresh = onRefresh,
        onCreate = onCreate,
        onUpdate = onUpdate,
        onDelete = onDelete,
        onTest = onTest,
        onSetActive = onSetActive,
        onOAuthStatus = onOAuthStatus,
        onOAuthLogin = onOAuthLogin,
        modifier = modifier,
    )
}

@Composable
private fun PlaceholderPanel(message: String, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp).testTag(BusinessUiTags.PLACEHOLDER_PANEL), contentAlignment = Alignment.Center) {
        Text(message)
    }
}

@Composable
private fun AgentPanelForShell(
    state: BusinessDesktopState,
    formState: DemoFormState,
    selectedModelId: String?,
    composerText: String,
    onComposerTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onReconnect: () -> Unit,
    onProviderSelected: (String, String) -> Unit,
    onCollapse: (() -> Unit)? = null,
    modifier: Modifier,
) {
    BusinessAgentPanel(
        state = state,
        formPatch = formState.suggestionPatch,
        selectedModelId = selectedModelId,
        composerText = composerText,
        onComposerTextChanged = onComposerTextChanged,
        onSend = onSend,
        onReconnect = onReconnect,
        onProviderSelected = onProviderSelected,
        onCollapse = onCollapse,
        modifier = modifier,
    )
}
