package com.wzx.babiq.desktop.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.ProviderInfo
import com.wzx.babiq.desktop.protocol.ProviderSaveParams
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.ProviderEditorState
import com.wzx.babiq.desktop.ui.common.chooseWorkspaceDirectory
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 设置页。
 *
 * P2-3 起这里从只读展示升级为“本地设置控制台”：Provider、沙箱策略、审批策略都会调用后端 JSON-RPC。
 * 当前 running turn 不会被中途修改，按钮可用性统一由 AppState.canEditSettings 控制。
 */
@Composable
fun SettingsPanel(
	state: AppState,
	onSelectWorkspace: (String) -> Unit,
	onSelectProvider: (String) -> Unit,
	onCreateProvider: (ProviderSaveParams) -> Unit,
	onDeleteProvider: (String) -> Unit,
	onTestProvider: (String) -> Unit,
	onSaveSandboxMode: (String) -> Unit,
	onSaveApprovalPolicy: (String) -> Unit,
	onSaveMemorySettings: (Boolean?, Boolean?, Boolean?, Boolean?) -> Unit,
	onConsolidateMemory: () -> Unit,
	onSearchMemory: (String) -> Unit,
	onSaveCapabilitySettings: (String, Boolean?, String?) -> Unit,
	onSearchCapabilities: (String) -> Unit,
) {
	Column(
		modifier = Modifier.fillMaxSize().padding(34.dp),
		verticalArrangement = Arrangement.spacedBy(18.dp),
	) {
		Text("设置", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
		state.settingsState.notice?.let { Text(it, color = BaBiQColors.Success) }
		state.settingsState.error?.let { Text("设置错误: $it", color = BaBiQColors.Danger) }
		if (!state.canEditSettings) {
			Text("当前 turn 运行中，设置会在本轮结束后开放修改。", color = BaBiQColors.Warning)
		}

		WorkspaceSettingsCard(state, onSelectWorkspace)
		PolicySettingsCard(
			state = state,
			onSaveSandboxMode = onSaveSandboxMode,
			onSaveApprovalPolicy = onSaveApprovalPolicy,
		)
		MemorySettingsSection(
			state = state,
			onSaveMemorySettings = onSaveMemorySettings,
			onConsolidateMemory = onConsolidateMemory,
			onSearchMemory = onSearchMemory,
		)
		CapabilityCenterSection(
			state = state,
			onSaveCapabilitySettings = onSaveCapabilitySettings,
			onSearchCapabilities = onSearchCapabilities,
		)
		ProviderSettingsCard(
			state = state,
			onSelectProvider = onSelectProvider,
			onCreateProvider = onCreateProvider,
			onDeleteProvider = onDeleteProvider,
			onTestProvider = onTestProvider,
		)
	}
}

/**
 * 工作区设置卡片。
 */
@Composable
private fun WorkspaceSettingsCard(
	state: AppState,
	onSelectWorkspace: (String) -> Unit,
) {
	SettingsCard("工作区") {
		Text("项目: ${state.workspace.projectName}")
		Text("路径: ${state.workspace.cwd}", color = BaBiQColors.Muted)
		state.workspace.permissionLabel?.let { label ->
			Text("当前权限: $label")
		}
		Button(
			enabled = state.canSwitchWorkspace,
			onClick = {
				// 设置页和输入框上下文条共用同一个目录选择器，保证行为一致。
				chooseWorkspaceDirectory(state.workspace.cwd)?.let(onSelectWorkspace)
			},
		) {
			Text("选择工作目录")
		}
	}
}

/**
 * 沙箱和审批策略设置卡片。
 */
@Composable
private fun PolicySettingsCard(
	state: AppState,
	onSaveSandboxMode: (String) -> Unit,
	onSaveApprovalPolicy: (String) -> Unit,
) {
	SettingsCard("运行策略") {
		Text("沙箱权限", fontWeight = FontWeight.Medium)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			PolicyButton("只读", "READ_ONLY", state.workspace.permissionMode, state.canEditSettings, onSaveSandboxMode)
			PolicyButton("工作区可写", "WORKSPACE_WRITE", state.workspace.permissionMode, state.canEditSettings, onSaveSandboxMode)
			PolicyButton("完全访问", "DANGER_FULL_ACCESS", state.workspace.permissionMode, state.canEditSettings, onSaveSandboxMode)
		}
		Text("审批策略", fontWeight = FontWeight.Medium)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			val currentApproval = state.settingsState.settings?.approvalPolicy
			PolicyButton("按需询问", "ON_REQUEST", currentApproval, state.canEditSettings, onSaveApprovalPolicy)
			PolicyButton("从不询问", "NEVER", currentApproval, state.canEditSettings, onSaveApprovalPolicy)
		}
	}
}

/**
 * Provider 设置卡片。
 */
@Composable
private fun ProviderSettingsCard(
	state: AppState,
	onSelectProvider: (String) -> Unit,
	onCreateProvider: (ProviderSaveParams) -> Unit,
	onDeleteProvider: (String) -> Unit,
	onTestProvider: (String) -> Unit,
) {
	var showForm by remember { mutableStateOf(false) }
	var draft by remember { mutableStateOf(ProviderEditorState()) }

	LaunchedEffect(state.settingsState.notice) {
		if (state.settingsState.notice?.contains("Provider 已保存") == true) {
			showForm = false
			draft = ProviderEditorState()
		}
	}

	SettingsCard("Provider") {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(enabled = state.canEditSettings, onClick = { showForm = true }) {
				Text("新增 Provider")
			}
		}
		if (showForm) {
			ProviderForm(
				draft = draft,
				enabled = state.canEditSettings && !state.settingsState.saving,
				onDraftChange = { draft = it },
				onSubmit = { onCreateProvider(draft.toSaveParams()) },
				onCancel = { showForm = false },
			)
		}
		if (state.providerState.providers.isEmpty()) {
			Text("当前没有可选择的后端 Provider，请检查后端配置。", color = BaBiQColors.Muted)
		} else {
			state.providerState.providers.forEach { provider ->
				ProviderRow(
					provider = provider,
					enabled = state.canEditSettings,
					onSelectProvider = onSelectProvider,
					onDeleteProvider = onDeleteProvider,
					onTestProvider = onTestProvider,
				)
			}
		}
	}
}

/**
 * Provider 新增表单。
 */
@Composable
private fun ProviderForm(
	draft: ProviderEditorState,
	enabled: Boolean,
	onDraftChange: (ProviderEditorState) -> Unit,
	onSubmit: () -> Unit,
	onCancel: () -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		OutlinedTextField(
			value = draft.providerId,
			onValueChange = { onDraftChange(draft.copy(providerId = it)) },
			enabled = enabled,
			label = { Text("Provider ID") },
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = draft.displayName,
			onValueChange = { onDraftChange(draft.copy(displayName = it)) },
			enabled = enabled,
			label = { Text("显示名称") },
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = draft.baseUrl,
			onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
			enabled = enabled,
			label = { Text("Base URL") },
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = draft.model,
			onValueChange = { onDraftChange(draft.copy(model = it)) },
			enabled = enabled,
			label = { Text("模型") },
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = draft.apiKey,
			onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
			enabled = enabled,
			label = { Text("API Key") },
			visualTransformation = PasswordVisualTransformation(),
			modifier = Modifier.fillMaxWidth(),
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(enabled = enabled, onClick = onSubmit) {
				Text("保存")
			}
			TextButton(onClick = onCancel) {
				Text("取消")
			}
		}
	}
}

/**
 * 单个 Provider 展示行。
 */
@Composable
private fun ProviderRow(
	provider: ProviderInfo,
	enabled: Boolean,
	onSelectProvider: (String) -> Unit,
	onDeleteProvider: (String) -> Unit,
	onTestProvider: (String) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(provider.displayName, fontWeight = FontWeight.Medium)
			if (provider.active) {
				Text("当前", color = BaBiQColors.Success)
			}
			if (provider.hasApiKey) {
				Text("已保存密钥", color = BaBiQColors.Muted)
			}
		}
		Text("id: ${provider.id}", color = BaBiQColors.Muted)
		Text("model: ${provider.model ?: provider.models.firstOrNull()?.label ?: "未配置"}", color = BaBiQColors.Muted)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			OutlinedButton(enabled = enabled, onClick = { onSelectProvider(provider.id) }) {
				Text("设为当前")
			}
			OutlinedButton(enabled = enabled, onClick = { onTestProvider(provider.id) }) {
				Text("测试连接")
			}
			TextButton(enabled = enabled, onClick = { onDeleteProvider(provider.id) }) {
				Text("删除")
			}
		}
	}
}

/**
 * 策略选择按钮。
 */
@Composable
private fun PolicyButton(
	label: String,
	value: String,
	current: String?,
	enabled: Boolean,
	onClick: (String) -> Unit,
) {
	val selected = current == value
	if (selected) {
		Button(enabled = enabled, onClick = { onClick(value) }) { Text(label) }
	} else {
		OutlinedButton(enabled = enabled, onClick = { onClick(value) }) { Text(label) }
	}
}

/**
 * 设置页里的基础卡片容器。
 */
@Composable
private fun SettingsCard(
	title: String,
	content: @Composable ColumnScope.() -> Unit,
) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Panel),
	) {
		Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
			content()
		}
	}
}
