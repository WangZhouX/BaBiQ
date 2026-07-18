package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.ui.agent.BusinessAgentPanel
import com.wzx.huitai.desktop.ui.form.DemoFormPanel
import com.wzx.huitai.desktop.ui.layout.BusinessDesktopLayoutMode
import com.wzx.huitai.desktop.ui.layout.BusinessDesktopLayoutPolicy
import com.wzx.huitai.desktop.ui.layout.CompactContentTab

/** 跨组件共享的稳定语义标签，供桌面 UI 自动化定位而不依赖视觉文本。 */
object BusinessUiTags {
    const val SIDEBAR = "business-sidebar"
    const val FORM_PANEL = "business-form-panel"
    const val AGENT_PANEL = "business-agent-panel"
}

/**
 * 按布局策略装配通用导航、七字段表单和 Agent 面板。
 *
 * 所有业务写入都以回调交给上层 controller；本组件不持有 ActionBus、协议连接或持久化对象。
 */
@Composable
fun BusinessDesktopShell(
    state: BusinessDesktopState,
    formState: DemoFormState,
    compactContentTab: CompactContentTab = CompactContentTab.FORM,
    selectedModelId: String? = null,
    composerText: String = "",
    onCompactContentTabSelected: (CompactContentTab) -> Unit = {},
    onFieldEdited: (fieldId: String, value: String) -> Unit = { _, _ -> },
    onAcceptSuggestion: (fieldId: String, baseRevision: Long) -> Unit = { _, _ -> },
    onAcceptAllSuggestions: (baseRevision: Long) -> Unit = {},
    onComposerTextChanged: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onReconnect: () -> Unit = {},
    onProviderSelected: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val layout = BusinessDesktopLayoutPolicy.resolve(maxWidth, compactContentTab)
        if (layout.mode == BusinessDesktopLayoutMode.COMPACT) {
            Column(Modifier.fillMaxSize()) {
                PrimaryTabRow(
                    selectedTabIndex = if (compactContentTab == CompactContentTab.FORM) 0 else 1,
                    modifier = Modifier.height(48.dp),
                ) {
                    Tab(
                        selected = compactContentTab == CompactContentTab.FORM,
                        onClick = { onCompactContentTabSelected(CompactContentTab.FORM) },
                        text = { Text("资料录入") },
                    )
                    Tab(
                        selected = compactContentTab == CompactContentTab.AGENT,
                        onClick = { onCompactContentTabSelected(CompactContentTab.AGENT) },
                        text = { Text("Agent") },
                    )
                }
                if (compactContentTab == CompactContentTab.FORM) {
                    DemoFormPanel(
                        state = formState,
                        suggestions = state.suggestions,
                        onFieldEdited = onFieldEdited,
                        onAcceptSuggestion = onAcceptSuggestion,
                        onAcceptAllSuggestions = onAcceptAllSuggestions,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    AgentPanelForShell(
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
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                BusinessSidebar(modifier = Modifier.width(layout.navigationWidth))
                DemoFormPanel(
                    state = formState,
                    suggestions = state.suggestions,
                    onFieldEdited = onFieldEdited,
                    onAcceptSuggestion = onAcceptSuggestion,
                    onAcceptAllSuggestions = onAcceptAllSuggestions,
                    modifier = Modifier.width(layout.formWidth),
                )
                AgentPanelForShell(
                    state = state,
                    formState = formState,
                    selectedModelId = selectedModelId,
                    composerText = composerText,
                    onComposerTextChanged = onComposerTextChanged,
                    onSend = onSend,
                    onReconnect = onReconnect,
                    onProviderSelected = onProviderSelected,
                    modifier = Modifier.width(layout.agentWidth),
                )
            }
        }
    }
}

/** 把 shell 参数原样交给 Agent 展示组件，保持主布局函数易读。 */
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
        modifier = modifier,
    )
}
