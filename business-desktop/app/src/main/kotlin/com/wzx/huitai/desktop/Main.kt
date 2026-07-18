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
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * 业务桌面可执行入口：先完成 lock/storage/child/authenticated connection，再创建 Compose window。
 * 初始化失败只记录稳定诊断并安全退出，绝不把命令、token、密码、路径或远端错误正文写入日志。
 */
fun main() {
    val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val root = try {
        runBlocking {
            BusinessDesktopCompositionRoot.start(
                ProductionBusinessDesktopCompositionFactory(
                    configuration = BusinessDesktopProductionConfiguration(
                        home = Path.of(System.getProperty("user.home")),
                        backendJar = BusinessDesktopProductionConfiguration.resolveBundledBackendJar(),
                    ),
                    parentScope = runtimeScope,
                ),
            )
        }
    } catch (_: Exception) {
        LoggerFactory.getLogger("BusinessDesktopStartup")
            .error("业务桌面初始化失败，请检查本机安装与安全配置")
        runtimeScope.cancel()
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
                            if (storage.screen.state.value.revision == baseRevision) {
                                storage.screen.dispatch(DemoFormEvent.AcceptSuggestion(fieldId))
                                uiScope.launch {
                                    view.production.workspaceController.publishPage(storage.screen.pageContext())
                                }
                            }
                        },
                        onAcceptAllSuggestions = { baseRevision ->
                            if (storage.screen.state.value.revision == baseRevision) {
                                storage.screen.dispatch(DemoFormEvent.AcceptAllSuggestions)
                                uiScope.launch {
                                    view.production.workspaceController.publishPage(storage.screen.pageContext())
                                }
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
