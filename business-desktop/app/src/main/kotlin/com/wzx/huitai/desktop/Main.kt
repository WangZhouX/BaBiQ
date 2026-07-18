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
import com.wzx.huitai.desktop.ui.action.ActionPreviewDialog
import com.wzx.huitai.desktop.ui.action.HighRiskApprovalDialog
import com.wzx.huitai.desktop.ui.layout.CompactContentTab
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
                onCloseRequest = { exitApplication() },
            ) {
                val desktopState by view.desktopState.collectAsState()
                val formState by view.formState.collectAsState()
                val decisionState by view.decisions.state.collectAsState()
                var compactTab by remember { mutableStateOf(CompactContentTab.FORM) }
                var composerText by remember { mutableStateOf("") }
                val uiScope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    runCatching { view.production.conversationController.refreshProviders() }
                }

                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = desktopState,
                        formState = formState,
                        compactContentTab = compactTab,
                        composerText = composerText,
                        onCompactContentTabSelected = { compactTab = it },
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
                        onComposerTextChanged = { composerText = it },
                        onSend = {
                            val text = composerText.trim()
                            if (text.isNotEmpty()) {
                                composerText = ""
                                uiScope.launch {
                                    val conversation = view.production.conversationController
                                    if (view.desktopState.value.currentThread == null) {
                                        conversation.createThread(storage.workspaceRoot.toString())
                                    }
                                    conversation.startTurn(text)
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
