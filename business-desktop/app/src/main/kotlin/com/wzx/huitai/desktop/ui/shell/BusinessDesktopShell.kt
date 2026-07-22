package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.conversation.BusinessProviderDraft
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsState
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessFieldSuggestion
import com.wzx.huitai.desktop.ui.agent.BusinessAgentPanel
import com.wzx.huitai.desktop.ui.agent.BusinessAssistantMascotButton
import com.wzx.huitai.desktop.ui.agent.BusinessAssistantResizeHandle
import com.wzx.huitai.desktop.ui.form.DemoFormPanel
import com.wzx.huitai.desktop.ui.layout.BusinessDesktopLayoutPolicy
import com.wzx.huitai.desktop.ui.settings.BusinessProviderSettingsPanel

/** 跨组件共享的稳定语义标签，供桌面 UI 自动化定位。 */
object BusinessUiTags {
    const val CONTENT = "business-shell-content"
    const val BUSINESS_REGION = "business-region"
    const val DIVIDER_SLOT = "business-assistant-divider-slot"
    const val COLLAPSED_ASSISTANT_CONTROL = "business-collapsed-assistant-control"
    const val EXPAND_WIDTH_MESSAGE = "business-assistant-expand-width-message"
    const val FORM_PANEL = "business-form-panel"
    const val AGENT_PANEL = "business-agent-panel"
    const val PLACEHOLDER_PANEL = "business-placeholder-panel"
}

/** 顶部一级导航和不遮挡业务内容的小律停靠分栏。 */
@Composable
fun BusinessDesktopShell(
    state: BusinessDesktopState,
    formState: DemoFormState,
    providerSettingsState: BusinessProviderSettingsState = BusinessProviderSettingsState(),
    selectedDestination: BusinessDesktopDestination = BusinessDesktopDestination.DATA_ENTRY,
    selectedModelId: String? = null,
    composerText: String = "",
    composerAttachments: List<BusinessAttachmentDraft> = emptyList(),
    attachmentError: String? = null,
    composerSubmitting: Boolean = false,
    agentPanelExpanded: Boolean = false,
    requestedAssistantWidth: Dp = BusinessDesktopLayoutPolicy.defaultAssistantWidth,
    onDestinationSelected: (BusinessDesktopDestination) -> Unit = {},
    onFieldEdited: (fieldId: String, value: String) -> Unit = { _, _ -> },
    onSuggestionsChanged: (Map<String, BusinessFieldSuggestion>) -> Unit = {},
    onAcceptSuggestion: (fieldId: String, baseRevision: Long) -> Unit = { _, _ -> },
    onAcceptAllSuggestions: (baseRevision: Long) -> Unit = {},
    onSaveDraft: () -> Unit = {},
    onSubmit: () -> Unit = {},
    onComposerTextChanged: (String) -> Unit = {},
    onChooseFiles: () -> Unit = {},
    onPasteImage: () -> Boolean = { false },
    onRemoveAttachment: (String) -> Unit = {},
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
    onRequestedAssistantWidthChange: (Dp) -> Unit = {},
    onShellComposed: () -> Unit = {},
    onTopNavigationComposed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SideEffect(onShellComposed)
    var expansionMessage by remember { mutableStateOf<String?>(null) }
    var resizeAccumulator by remember { mutableStateOf(requestedAssistantWidth) }
    LaunchedEffect(requestedAssistantWidth) {
        resizeAccumulator = requestedAssistantWidth
    }
    Column(modifier.fillMaxSize()) {
        BusinessTopNavigation(
            selectedDestination = selectedDestination,
            onDestinationSelected = onDestinationSelected,
            onComposed = onTopNavigationComposed,
        )
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            BusinessSidebar(
                selected = selectedDestination,
                onSelected = onDestinationSelected,
                modifier = Modifier.width(BusinessDesktopLayoutPolicy.navigationWidth),
            )
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag(BusinessUiTags.CONTENT),
            ) {
                val availableWidth = maxWidth
                val layout = BusinessDesktopLayoutPolicy.resolveDocked(
                    availableWidth = availableWidth,
                    assistantExpanded = agentPanelExpanded,
                    requestedAssistantWidth = requestedAssistantWidth,
                )
                LaunchedEffect(layout.assistantExpanded) {
                    if (layout.assistantExpanded) expansionMessage = null
                }

                if (layout.assistantExpanded) {
                    Box(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .width(layout.businessWidth)
                                    .fillMaxSize()
                                    .testTag(BusinessUiTags.BUSINESS_REGION),
                            ) {
                                BusinessContent(
                                    destination = selectedDestination,
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
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Box(
                                Modifier
                                    .width(layout.dividerWidth)
                                    .fillMaxSize()
                                    .testTag(BusinessUiTags.DIVIDER_SLOT),
                            )
                            AgentPanelForShell(
                                state = state,
                                formState = formState,
                                selectedModelId = selectedModelId,
                                composerText = composerText,
                                composerAttachments = composerAttachments,
                                attachmentError = attachmentError,
                                composerSubmitting = composerSubmitting,
                                onComposerTextChanged = onComposerTextChanged,
                                onChooseFiles = onChooseFiles,
                                onPasteImage = onPasteImage,
                                onRemoveAttachment = onRemoveAttachment,
                                onSend = onSend,
                                onReconnect = onReconnect,
                                onProviderSelected = onProviderSelected,
                                mascot = {
                                    BusinessAssistantMascotButton(
                                        expanded = true,
                                        onToggle = { onAgentPanelExpandedChange(false) },
                                    )
                                },
                                modifier = Modifier.width(layout.assistantWidth),
                            )
                        }
                        BusinessAssistantResizeHandle(
                            onResizeBy = { delta ->
                                val nextWidth = BusinessDesktopLayoutPolicy.resizeAssistantWidth(
                                    current = resizeAccumulator,
                                    dragDeltaX = delta,
                                    availableWidth = availableWidth,
                                )
                                resizeAccumulator = nextWidth
                                onRequestedAssistantWidthChange(nextWidth)
                            },
                            modifier = Modifier
                                .offset(x = layout.businessWidth - 2.dp)
                                .requiredWidth(12.dp)
                                .fillMaxHeight(),
                        )
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .width(layout.businessWidth)
                                .fillMaxHeight()
                                .testTag(BusinessUiTags.BUSINESS_REGION),
                        ) {
                            BusinessContent(
                                destination = selectedDestination,
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
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(layout.assistantWidth)
                                .fillMaxHeight()
                                .testTag(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL),
                        ) {
                            expansionMessage?.let {
                                Text(
                                    text = it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 4.dp)
                                        .padding(bottom = 112.dp)
                                        .testTag(BusinessUiTags.EXPAND_WIDTH_MESSAGE),
                                )
                            }
                            BusinessAssistantMascotButton(
                                expanded = false,
                                onToggle = {
                                    if (layout.canExpand) {
                                        expansionMessage = null
                                        onAgentPanelExpandedChange(true)
                                    } else {
                                        expansionMessage = "窗口宽度不足，请先最大化或放大窗口"
                                    }
                                },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BusinessContent(
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
        BusinessDesktopDestination.WORKBENCH -> PlaceholderPanel("工作台功能将在后续阶段开放", modifier)
        BusinessDesktopDestination.DATA_ENTRY -> FormPanelForShell(
            state = state,
            formState = formState,
            onFieldEdited = onFieldEdited,
            onSuggestionsChanged = onSuggestionsChanged,
            onAcceptSuggestion = onAcceptSuggestion,
            onAcceptAllSuggestions = onAcceptAllSuggestions,
            onSaveDraft = onSaveDraft,
            onSubmit = onSubmit,
            modifier = modifier,
        )
        BusinessDesktopDestination.RUN_HISTORY -> PlaceholderPanel("运行记录功能将在后续阶段开放", modifier)
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
            modifier = modifier,
        )
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
    Box(
        modifier.fillMaxSize().padding(24.dp).testTag(BusinessUiTags.PLACEHOLDER_PANEL),
        contentAlignment = Alignment.Center,
    ) {
        Text(message)
    }
}

@Composable
private fun AgentPanelForShell(
    state: BusinessDesktopState,
    formState: DemoFormState,
    selectedModelId: String?,
    composerText: String,
    composerAttachments: List<BusinessAttachmentDraft>,
    attachmentError: String?,
    composerSubmitting: Boolean,
    onComposerTextChanged: (String) -> Unit,
    onChooseFiles: () -> Unit,
    onPasteImage: () -> Boolean,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onReconnect: () -> Unit,
    onProviderSelected: (String, String) -> Unit,
    mascot: @Composable () -> Unit,
    modifier: Modifier,
) {
    BusinessAgentPanel(
        state = state,
        formPatch = formState.suggestionPatch,
        selectedModelId = selectedModelId,
        composerText = composerText,
        composerAttachments = composerAttachments,
        attachmentError = attachmentError,
        composerSubmitting = composerSubmitting,
        onComposerTextChanged = onComposerTextChanged,
        onChooseFiles = onChooseFiles,
        onPasteImage = onPasteImage,
        onRemoveAttachment = onRemoveAttachment,
        onSend = onSend,
        onReconnect = onReconnect,
        onProviderSelected = onProviderSelected,
        mascot = mascot,
        modifier = modifier,
    )
}
