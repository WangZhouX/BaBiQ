package com.wzx.huitai.desktop.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderDraft
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsNoticeLevel
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsState

/** 业务桌面原生 Provider 设置页；API Key 仅由编辑对话框的局部 remember 状态持有。 */
@Composable
fun BusinessProviderSettingsPanel(
    state: BusinessProviderSettingsState,
    onRefresh: () -> Unit = {},
    onCreate: suspend (BusinessProviderDraft) -> Boolean = { false },
    onUpdate: suspend (BusinessProviderDraft) -> Boolean = { false },
    onDelete: (String) -> Unit = {},
    onTest: (String) -> Unit = {},
    onSetActive: (providerId: String, modelId: String?) -> Unit = { _, _ -> },
    onOAuthStatus: (String) -> Unit = {},
    onOAuthLogin: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var editor by remember { mutableStateOf<BusinessProviderEditorSession?>(null) }
    var editorOrdinal by remember { mutableIntStateOf(0) }
    var deletingProviderId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("provider-settings-panel")
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Provider 设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("配置模型端点、认证方式和当前 Provider；密钥不会回显。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = state.operationsEnabled && !state.loading,
                    onClick = onRefresh,
                    modifier = Modifier.testTag("provider-refresh-action"),
                ) { Text(if (state.loading) "刷新中" else "刷新") }
                Button(
                    enabled = state.operationsEnabled,
                    onClick = {
                        editorOrdinal += 1
                        editor = BusinessProviderEditorSession(
                            identity = "create:$editorOrdinal",
                            mode = BusinessProviderEditorMode.CREATE,
                            draft = BusinessProviderEditorDraft(),
                            hasPersistedOAuth = false,
                            hasStoredApiKey = false,
                        )
                    },
                    modifier = Modifier.testTag("provider-add-action"),
                ) { Text("新增 Provider") }
            }
        }

        state.notice?.let { notice ->
            Text(
                notice.message,
                color = when (notice.level) {
                    BusinessProviderSettingsNoticeLevel.ERROR -> MaterialTheme.colorScheme.error
                    BusinessProviderSettingsNoticeLevel.SUCCESS -> MaterialTheme.colorScheme.primary
                    BusinessProviderSettingsNoticeLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (state.providers.isEmpty()) {
            Text("暂无 Provider 配置", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.providers.forEach { provider ->
                ProviderSettingsCard(
                    provider = provider,
                    operationsEnabled = state.operationsEnabled,
                    busy = state.busyProviderId == provider.id,
                    onEdit = {
                        editor = BusinessProviderEditorSession(
                            identity = "edit:${provider.id}",
                            mode = BusinessProviderEditorMode.EDIT,
                            draft = provider.toEditorDraft(),
                            hasPersistedOAuth = provider.type.equals("ANTHROPIC", ignoreCase = true) &&
                                provider.authMode.equals("oauth_cli", ignoreCase = true),
                            hasStoredApiKey = provider.hasApiKey,
                        )
                    },
                    onCopy = {
                        editorOrdinal += 1
                        editor = BusinessProviderEditorSession(
                            identity = "copy:${provider.id}:$editorOrdinal",
                            mode = BusinessProviderEditorMode.CREATE,
                            draft = provider.toEditorDraft(copy = true),
                            hasPersistedOAuth = false,
                            hasStoredApiKey = false,
                        )
                    },
                    onDelete = { deletingProviderId = provider.id },
                    onTest = { onTest(provider.id) },
                    onSetActive = { onSetActive(provider.id, provider.model) },
                )
            }
        }
    }

    editor?.let { session ->
        BusinessProviderEditorDialog(
            session = session,
            operationsEnabled = state.operationsEnabled,
            connectionGeneration = state.connectionGeneration,
            oauthStatuses = state.oauthStatus,
            onSave = { draft ->
                if (session.mode == BusinessProviderEditorMode.EDIT) onUpdate(draft) else onCreate(draft)
            },
            onOAuthStatus = onOAuthStatus,
            onOAuthLogin = onOAuthLogin,
            onDismiss = { editor = null },
        )
    }

    deletingProviderId?.let { providerId ->
        AlertDialog(
            onDismissRequest = { deletingProviderId = null },
            title = { Text("删除 Provider") },
            text = { Text("确认删除 $providerId？该操作会禁用此 Provider。") },
            confirmButton = {
                Button(
                    enabled = state.operationsEnabled,
                    onClick = {
                        onDelete(providerId)
                        deletingProviderId = null
                    },
                    modifier = Modifier.testTag("provider-delete-confirm"),
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingProviderId = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProviderSettingsCard(
    provider: BusinessProvider,
    operationsEnabled: Boolean,
    busy: Boolean,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onSetActive: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(provider.displayName, fontWeight = FontWeight.Bold)
                    Text(provider.id, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (provider.active) Text("当前", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text(provider.type)
            Text(provider.authMode)
            if (provider.baseUrl.isNotBlank()) Text(provider.baseUrl)
            Text(provider.model)
            Text(provider.contextWindow.toString())
            Text(if (provider.hasApiKey) "API Key 已配置" else "API Key 未配置")
            if (!provider.enabled) Text("已禁用", color = MaterialTheme.colorScheme.error)
            if (busy) Text("操作中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    enabled = operationsEnabled && !busy,
                    onClick = onSetActive,
                    modifier = Modifier.testTag("provider-activate-${provider.id}"),
                ) { Text("设为当前") }
                OutlinedButton(
                    enabled = operationsEnabled && !busy,
                    onClick = onEdit,
                    modifier = Modifier.testTag("provider-edit-${provider.id}"),
                ) { Text("编辑") }
                OutlinedButton(
                    enabled = operationsEnabled && !busy,
                    onClick = onCopy,
                    modifier = Modifier.testTag("provider-copy-${provider.id}"),
                ) { Text("复制") }
                OutlinedButton(
                    enabled = operationsEnabled && !busy,
                    onClick = onTest,
                    modifier = Modifier.testTag("provider-test-${provider.id}"),
                ) { Text("测试") }
                TextButton(
                    enabled = operationsEnabled && !busy,
                    onClick = onDelete,
                    modifier = Modifier.testTag("provider-delete-${provider.id}"),
                ) { Text("删除") }
            }
        }
    }
}
