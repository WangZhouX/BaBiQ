package com.wzx.huitai.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderDraft
import com.wzx.huitai.agent.conversation.BusinessProviderOAuthStatus

internal enum class BusinessProviderEditorMode {
    CREATE,
    EDIT,
}

internal data class BusinessProviderEditorDraft(
    val providerId: String = "",
    val displayName: String = "",
    val type: String = "OPENAI_COMPATIBLE",
    val authMode: String = "api_key",
    val baseUrl: String = "",
    val model: String = "",
    val contextWindowText: String = "0",
    val enabled: Boolean = true,
)

internal data class BusinessProviderEditorSession(
    val identity: String,
    val mode: BusinessProviderEditorMode,
    val draft: BusinessProviderEditorDraft,
)

internal fun BusinessProvider.toEditorDraft(copy: Boolean = false): BusinessProviderEditorDraft =
    BusinessProviderEditorDraft(
        providerId = id + if (copy) "-copy" else "",
        displayName = displayName + if (copy) " 副本" else "",
        type = type,
        authMode = authMode,
        baseUrl = baseUrl,
        model = model,
        contextWindowText = contextWindow.toString(),
        enabled = enabled,
    )

@Composable
internal fun BusinessProviderEditorDialog(
    session: BusinessProviderEditorSession,
    operationsEnabled: Boolean,
    connectionGeneration: Long,
    oauthStatus: BusinessProviderOAuthStatus?,
    onSave: (BusinessProviderDraft) -> Unit,
    onOAuthStatus: () -> Unit,
    onOAuthLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(session.identity) { mutableStateOf(session.draft) }
    var apiKey by remember(session.identity) { mutableStateOf("") }
    val contextWindow = draft.contextWindowText.toIntOrNull()
    val anthropicOAuth = draft.type.equals("ANTHROPIC", ignoreCase = true) &&
        draft.authMode.equals("oauth_cli", ignoreCase = true)
    val valid = draft.providerId.isNotBlank() &&
        draft.displayName.isNotBlank() &&
        draft.type.isNotBlank() &&
        draft.authMode.isNotBlank() &&
        draft.model.isNotBlank() &&
        contextWindow != null && contextWindow >= 0 &&
        (!draft.authMode.equals("oauth_cli", ignoreCase = true) || anthropicOAuth)

    LaunchedEffect(connectionGeneration, operationsEnabled) {
        apiKey = ""
    }
    LaunchedEffect(anthropicOAuth) {
        if (anthropicOAuth) apiKey = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (session.mode == BusinessProviderEditorMode.EDIT) "编辑 Provider" else "新增 Provider")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draft.providerId,
                    onValueChange = { draft = draft.copy(providerId = it) },
                    enabled = operationsEnabled && session.mode == BusinessProviderEditorMode.CREATE,
                    label = { Text("Provider ID") },
                    modifier = Modifier.fillMaxWidth().testTag("provider-id-input"),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.displayName,
                    onValueChange = { draft = draft.copy(displayName = it) },
                    enabled = operationsEnabled,
                    label = { Text("显示名称") },
                    modifier = Modifier.fillMaxWidth().testTag("provider-display-name-input"),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.type,
                    onValueChange = { draft = draft.copy(type = it) },
                    enabled = operationsEnabled,
                    label = { Text("类型") },
                    modifier = Modifier.fillMaxWidth().testTag("provider-type-input"),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.authMode,
                    onValueChange = { draft = draft.copy(authMode = it) },
                    enabled = operationsEnabled,
                    label = { Text("认证方式") },
                    modifier = Modifier.fillMaxWidth().testTag("provider-auth-mode-input"),
                    singleLine = true,
                )
                if (anthropicOAuth) {
                    Text(oauthStatus?.message ?: "尚未检查 Claude CLI OAuth 状态")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = operationsEnabled,
                            onClick = onOAuthStatus,
                            modifier = Modifier.testTag("provider-oauth-status-action"),
                        ) { Text("查询 OAuth 状态") }
                        OutlinedButton(
                            enabled = operationsEnabled,
                            onClick = onOAuthLogin,
                            modifier = Modifier.testTag("provider-oauth-login-action"),
                        ) { Text("启动 OAuth 登录") }
                    }
                }
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { draft = draft.copy(baseUrl = it) },
                    enabled = operationsEnabled,
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth().testTag("provider-base-url-input"),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { draft = draft.copy(model = it) },
                    enabled = operationsEnabled,
                    label = { Text("模型") },
                    modifier = Modifier.fillMaxWidth().testTag("provider-model-input"),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.contextWindowText,
                    onValueChange = { draft = draft.copy(contextWindowText = it) },
                    enabled = operationsEnabled,
                    label = { Text("Context Window") },
                    supportingText = {
                        if (contextWindow == null || contextWindow < 0) Text("请输入非负整数")
                    },
                    modifier = Modifier.fillMaxWidth().testTag("provider-context-window-input"),
                    singleLine = true,
                )
                if (!anthropicOAuth) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        enabled = operationsEnabled,
                        label = {
                            Text(
                                if (session.mode == BusinessProviderEditorMode.EDIT) {
                                    "API Key（留空沿用）"
                                } else {
                                    "API Key"
                                },
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("provider-api-key-input"),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = operationsEnabled && valid,
                onClick = {
                    onSave(
                        BusinessProviderDraft(
                            providerId = draft.providerId.trim(),
                            displayName = draft.displayName.trim(),
                            type = draft.type.trim(),
                            authMode = draft.authMode.trim(),
                            baseUrl = draft.baseUrl.trim(),
                            model = draft.model.trim(),
                            apiKey = apiKey.takeIf { !anthropicOAuth && it.isNotBlank() },
                            contextWindow = requireNotNull(contextWindow),
                            enabled = draft.enabled,
                        ),
                    )
                    apiKey = ""
                },
                modifier = Modifier.testTag("provider-save-action"),
            ) { Text(if (session.mode == BusinessProviderEditorMode.EDIT) "保存修改" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("provider-editor-cancel")) {
                Text("取消")
            }
        },
    )
}
