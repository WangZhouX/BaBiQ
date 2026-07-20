package com.wzx.huitai.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.wzx.huitai.demo.model.DemoFormEvent
import com.wzx.huitai.desktop.app.BusinessDesktopCompositionRoot
import com.wzx.huitai.desktop.app.BusinessDesktopProductionConfiguration
import com.wzx.huitai.desktop.app.ProductionBusinessDesktopCompositionFactory
import com.wzx.huitai.desktop.decision.ConfirmationDecisionDialogState
import com.wzx.huitai.desktop.decision.HighRiskApprovalDialogState
import com.wzx.huitai.desktop.controller.BusinessComposerDraftState
import com.wzx.huitai.desktop.controller.BusinessComposerSendCoordinator
import com.wzx.huitai.desktop.controller.mergeBusinessComposerAttachments
import com.wzx.huitai.desktop.controller.safeComposerAttachmentError
import com.wzx.huitai.desktop.runtime.BusinessLocalAttachmentException
import com.wzx.huitai.desktop.ui.agent.BusinessAttachmentSelectionException
import com.wzx.huitai.desktop.ui.action.ActionPreviewDialog
import com.wzx.huitai.desktop.ui.action.HighRiskApprovalDialog
import com.wzx.huitai.desktop.ui.shell.BusinessDesktopDestination
import com.wzx.huitai.desktop.ui.shell.BusinessDesktopShell
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import com.wzx.huitai.desktop.smoke.PackagedSmokeProbe
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.presentation.form.FormPatch
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * 业务桌面可执行入口：先完成 lock/storage/child/authenticated connection，再创建 Compose window。
 * 初始化失败只记录稳定诊断并安全退出，绝不把命令、token、密码、路径或远端错误正文写入日志。
 */
fun main() {
    val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val startup = try {
        val smokeProbe = PackagedSmokeProbe.fromEnvironment()
        val factory = ProductionBusinessDesktopCompositionFactory(
            configuration = BusinessDesktopProductionConfiguration(
                home = BusinessDesktopProductionConfiguration.resolveHome(),
                backendJar = BusinessDesktopProductionConfiguration.resolveBundledBackendJar(),
                frameworkDemoIdentity = System.getenv("HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY") == "1",
            ),
            parentScope = runtimeScope,
        )
        DesktopStartup(
            root = runBlocking { BusinessDesktopCompositionRoot.start(factory) },
            factory = factory,
            smokeProbe = smokeProbe,
        )
    } catch (_: Exception) {
        LoggerFactory.getLogger("BusinessDesktopStartup")
            .error("业务桌面初始化失败，请检查本机安装与安全配置")
        runtimeScope.cancel()
        return
    }
    val root = startup.root
    val factory = startup.factory
    val smokeProbe = startup.smokeProbe
    if (smokeProbe != null) {
        try {
            runBlocking { smokeProbe.write(factory.packagedSmokeEvidence()) }
        } catch (_: Exception) {
            LoggerFactory.getLogger("BusinessDesktopSmoke")
                .error("业务桌面安装包烟测失败")
        } finally {
            try {
                runBlocking { root.shutdown() }
            } finally {
                runtimeScope.cancel()
            }
        }
        return
    }
    val view = requireNotNull(root.runtimeView)
    val storage = requireNotNull(root.productionStorage)

    try {
        application {
            Window(
                title = "汇泰业务桌面 Agent",
                state = androidx.compose.ui.window.rememberWindowState(width = 1440.dp, height = 900.dp),
                onCloseRequest = {
                    closeBusinessDesktop(
                        shutdown = { root.shutdown() },
                        cancelRuntime = runtimeScope::cancel,
                        exitApplication = ::exitApplication,
                    )?.let {
                        LoggerFactory.getLogger("BusinessDesktopShutdown")
                            .error("Business desktop resource shutdown failed")
                    }
                },
            ) {
                val desktopState by view.desktopState.collectAsState()
                val formState by view.formState.collectAsState()
                val decisionState by view.decisions.state.collectAsState()
                val providerSettingsState by view.production.providerSettingsController.state.collectAsState()
                var selectedDestination by remember { mutableStateOf(BusinessDesktopDestination.DATA_ENTRY) }
                var composerDraft by remember { mutableStateOf(BusinessComposerDraftState()) }
                var composerAttachmentError by remember {
                    mutableStateOf<com.wzx.huitai.desktop.controller.BusinessComposerAttachmentError?>(null)
                }
                var agentPanelExpanded by remember { mutableStateOf(true) }
                val uiScope = rememberCoroutineScope()
                val sendCoordinator = remember(view, storage) {
                    BusinessComposerSendCoordinator { text, attachments ->
                        val conversation = view.production.conversationController
                        if (view.desktopState.value.currentThread == null) {
                            conversation.createThread(storage.workspaceRoot.toString())
                        }
                        conversation.startTurn(text, attachments)
                    }
                }

                LaunchedEffect(Unit) {
                    runCatching { view.production.conversationController.refreshProviders() }
                }

                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = desktopState,
                        formState = formState,
                        providerSettingsState = providerSettingsState,
                        selectedDestination = selectedDestination,
                        composerText = composerDraft.text,
                        composerAttachments = composerDraft.attachments,
                        attachmentError = composerAttachmentError?.let { "${it.code}: ${it.message}" },
                        agentPanelExpanded = agentPanelExpanded,
                        onDestinationSelected = { selectedDestination = it },
                        onAgentPanelExpandedChange = { agentPanelExpanded = it },
                        onFieldEdited = { fieldId, value ->
                            storage.screen.dispatch(DemoFormEvent.EditField(fieldId, value))
                            uiScope.launch {
                                view.production.workspaceController.publishPage(storage.screen.pageContext())
                            }
                        },
                        onSuggestionsChanged = { suggestions ->
                            view.production.workspaceController.updateSuggestions(suggestions.values.toList())
                        },
                        onAcceptSuggestion = { fieldId, baseRevision ->
                            val installed = storage.screen.state.value.suggestionPatch
                            val change = installed?.changes?.singleOrNull { it.fieldId == fieldId }
                            if (installed?.baseRevision == baseRevision && change != null) {
                                val patch = FormPatch(installed.pageId, installed.baseRevision, listOf(change))
                                uiScope.launch {
                                    val executionId = UUID.randomUUID().toString()
                                    view.production.workspaceController.executeUserAction(
                                        executionId = executionId,
                                        actionId = "form.apply_patch",
                                        actionVersion = 1,
                                        input = actionInput(executionId, patch),
                                    )
                                    view.production.workspaceController.publishPage(storage.screen.pageContext())
                                }
                            }
                        },
                        onAcceptAllSuggestions = { baseRevision ->
                            val patch = storage.screen.state.value.suggestionPatch
                            if (patch?.baseRevision == baseRevision) {
                                uiScope.launch {
                                    val executionId = UUID.randomUUID().toString()
                                    view.production.workspaceController.executeUserAction(
                                        executionId = executionId,
                                        actionId = "form.apply_patch",
                                        actionVersion = 1,
                                        input = actionInput(executionId, patch),
                                    )
                                    view.production.workspaceController.publishPage(storage.screen.pageContext())
                                }
                            }
                        },
                        onSaveDraft = {
                            uiScope.launch {
                                val executionId = UUID.randomUUID().toString()
                                view.production.workspaceController.executeUserAction(
                                    executionId = executionId,
                                    actionId = "demo.save_draft",
                                    actionVersion = 1,
                                    input = buildJsonObject { put("executionId", executionId) },
                                )
                            }
                        },
                        onSubmit = {
                            uiScope.launch {
                                val executionId = UUID.randomUUID().toString()
                                view.production.workspaceController.executeUserAction(
                                    executionId = executionId,
                                    actionId = "demo.submit",
                                    actionVersion = 1,
                                    input = buildJsonObject { put("executionId", executionId) },
                                )
                            }
                        },
                        onComposerTextChanged = { composerDraft = composerDraft.copy(text = it) },
                        onChooseFiles = {
                            try {
                                val historyAttachments = desktopState.messages
                                    .filterIsInstance<com.wzx.huitai.agent.conversation.BusinessThreadItem.UserMessage>()
                                    .flatMap { it.attachments }
                                val additions = view.production.attachmentPicker.choose(
                                    currentDrafts = composerDraft.attachments,
                                    existingIds = historyAttachments.mapTo(hashSetOf()) { it.id },
                                    existingDisplayIds = historyAttachments.mapTo(hashSetOf()) { it.displayId },
                                )
                                if (additions.isNotEmpty()) {
                                    composerDraft = composerDraft.copy(
                                        attachments = mergeBusinessComposerAttachments(
                                            composerDraft.attachments,
                                            additions,
                                        ),
                                    )
                                }
                                composerAttachmentError = null
                            } catch (failure: BusinessAttachmentSelectionException) {
                                composerAttachmentError = safeComposerAttachmentError(failure.code, failure.message)
                            } catch (failure: Exception) {
                                composerAttachmentError = safeComposerAttachmentError(failure)
                            }
                        },
                        onPasteImage = {
                            try {
                                val historyAttachments = desktopState.messages
                                    .filterIsInstance<com.wzx.huitai.agent.conversation.BusinessThreadItem.UserMessage>()
                                    .flatMap { it.attachments }
                                val existingIds = historyAttachments.mapTo(hashSetOf()) { it.id }.apply {
                                    addAll(composerDraft.attachments.map { it.id })
                                }
                                val existingDisplayIds = historyAttachments.mapTo(hashSetOf()) { it.displayId }.apply {
                                    addAll(composerDraft.attachments.map { it.displayId })
                                }
                                val captured = view.production.clipboardImageAttachmentStore.capture(
                                    existingIds = existingIds,
                                    existingDisplayIds = existingDisplayIds,
                                )
                                if (captured == null) {
                                    false
                                } else {
                                    composerDraft = composerDraft.copy(
                                        attachments = mergeBusinessComposerAttachments(
                                            composerDraft.attachments,
                                            listOf(captured),
                                        ),
                                    )
                                    composerAttachmentError = null
                                    true
                                }
                            } catch (failure: BusinessLocalAttachmentException) {
                                composerAttachmentError = safeComposerAttachmentError(failure.code, failure.message)
                                true
                            } catch (failure: Exception) {
                                composerAttachmentError = safeComposerAttachmentError(failure)
                                true
                            }
                        },
                        onRemoveAttachment = { attachmentId ->
                            composerDraft = composerDraft.copy(
                                attachments = composerDraft.attachments.filterNot { it.id == attachmentId },
                            )
                            composerAttachmentError = null
                        },
                        onSend = {
                            val captured = composerDraft
                            if (captured.text.isNotBlank() || captured.attachments.isNotEmpty()) {
                                uiScope.launch {
                                    val result = sendCoordinator.submit(captured)
                                    composerDraft = sendCoordinator.reconcile(
                                        current = composerDraft,
                                        captured = captured,
                                        result = result,
                                    )
                                    if (result.succeeded) {
                                        composerAttachmentError = null
                                    }
                                }
                            }
                        },
                        onProviderSelected = { providerId, modelId ->
                            uiScope.launch {
                                view.production.conversationController.selectProvider(providerId, modelId)
                            }
                        },
                        onReconnect = {
                            uiScope.launch { view.production.desktopCoordinator.manualRetry() }
                        },
                        onProviderRefresh = {
                            uiScope.launch { view.production.providerSettingsController.refresh() }
                        },
                        onProviderCreate = { draft ->
                            view.production.providerSettingsController.create(draft) != null
                        },
                        onProviderUpdate = { draft ->
                            view.production.providerSettingsController.update(draft) != null
                        },
                        onProviderDelete = { providerId ->
                            uiScope.launch { view.production.providerSettingsController.delete(providerId) }
                        },
                        onProviderTest = { providerId ->
                            uiScope.launch { view.production.providerSettingsController.test(providerId) }
                        },
                        onProviderActivated = { providerId, modelId ->
                            uiScope.launch { view.production.providerSettingsController.setActive(providerId, modelId) }
                        },
                        onProviderOAuthStatus = { providerId ->
                            uiScope.launch { view.production.providerSettingsController.oauthStatus(providerId) }
                        },
                        onProviderOAuthLogin = { providerId ->
                            uiScope.launch { view.production.providerSettingsController.oauthLogin(providerId) }
                        },
                    )

                    when (val dialog = decisionState.activeDialog) {
                        is ConfirmationDecisionDialogState -> ActionPreviewDialog(
                            state = dialog,
                            onConfirm = { view.decisions.accept(dialog.executionId) },
                            onCancel = { view.decisions.reject(dialog.executionId) },
                        )
                        is HighRiskApprovalDialogState -> HighRiskApprovalDialog(
                            state = dialog,
                            onApprove = { view.decisions.approve(dialog.executionId) },
                            onDeny = { view.decisions.deny(dialog.executionId) },
                        )
                        null -> Unit
                    }
                }
            }
        }
    } finally {
        try {
            runBlocking { root.shutdown() }
        } finally {
            runtimeScope.cancel()
        }
    }
}

private data class DesktopStartup(
    val root: BusinessDesktopCompositionRoot,
    val factory: ProductionBusinessDesktopCompositionFactory,
    val smokeProbe: PackagedSmokeProbe?,
)

private fun actionInput(executionId: String, patch: FormPatch) = buildJsonObject {
    put("executionId", executionId)
    put("patch", ApplicationProtocol.JSON.encodeToJsonElement(FormPatch.serializer(), patch).jsonObject)
}

/** Compose 窗口退出前同步收束 composition root，避免原生 launcher 退出后遗留 Agent 子进程。 */
internal fun closeBusinessDesktop(
    shutdown: suspend () -> Unit,
    cancelRuntime: () -> Unit,
    exitApplication: () -> Unit,
): Throwable? {
    var first: Throwable? = null
    fun record(failure: Throwable) {
        first?.addSuppressed(failure) ?: run { first = failure }
    }

    runCatching { runBlocking { shutdown() } }.exceptionOrNull()?.let(::record)
    runCatching(cancelRuntime).exceptionOrNull()?.let(::record)
    runCatching(exitApplication).exceptionOrNull()?.let(::record)
    return first
}
