package com.wzx.babiq.desktop.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.McpServerInfo
import com.wzx.babiq.desktop.protocol.McpToolInfo
import com.wzx.babiq.desktop.protocol.ProviderInfo
import com.wzx.babiq.desktop.protocol.ProviderSaveParams
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.ProviderEditorState
import com.wzx.babiq.desktop.state.Screen
import com.wzx.babiq.desktop.ui.common.chooseWorkspaceDirectory
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 设置页标题栏的导航模型。
 *
 * @property title 页面主标题。
 * @property backLabel 返回按钮文案；使用箭头符号让用户一眼知道这是导航行为。
 * @property backTarget 点击返回后切换到的主页面，当前固定回到聊天页，不触发任何后端设置写入。
 */
data class SettingsHeaderModel(
	val title: String,
	val backLabel: String,
	val backTarget: Screen,
)

/**
 * 设置页的真实产品分区。
 *
 * Figma 中 Provider、权限与审批、本地 MCP、记忆、能力都是同一个设置工作台下的标签页；
 * 这里把它们抽成枚举，避免 UI 文案切换和后端功能入口散落在多个地方。
 */
enum class SettingsTab {
	Provider,
	Approval,
	Mcp,
	Memory,
	Capability,
}

/**
 * 设置标签页渲染模型。
 *
 * @property tab 稳定枚举值，UI 切换和测试都依赖它而不是中文文案。
 * @property label 显示给用户的标签。
 */
data class SettingsTabItem(
	val tab: SettingsTab,
	val label: String,
)

/**
 * 审批策略按钮模型。
 *
 * @property label 展示文案。
 * @property value 后端 ApprovalPolicy 枚举名。
 * @property description 简短说明，帮助用户理解策略对下一轮 Agent 的影响。
 */
data class ApprovalPolicyOption(
	val label: String,
	val value: String,
	val description: String,
)

/**
 * 构造设置页标题栏模型。
 */
fun buildSettingsHeaderModel(): SettingsHeaderModel =
	SettingsHeaderModel(
		title = "设置",
		backLabel = "← 返回对话",
		backTarget = Screen.Chat,
	)

/**
 * 返回 Figma P3 原型里的设置页标签顺序。
 */
fun settingsTabs(): List<SettingsTabItem> =
	listOf(
		SettingsTabItem(SettingsTab.Provider, "Provider"),
		SettingsTabItem(SettingsTab.Approval, "权限与审批"),
		SettingsTabItem(SettingsTab.Mcp, "本地 MCP"),
		SettingsTabItem(SettingsTab.Memory, "记忆"),
		SettingsTabItem(SettingsTab.Capability, "能力"),
	)

/**
 * 把已有 Provider 安全地回填到编辑草稿。
 *
 * API Key 不会从后端返回明文，所以编辑时保持空字符串；用户留空代表沿用后端已有密钥。
 */
fun providerDraftFrom(provider: ProviderInfo): ProviderEditorState =
	ProviderEditorState(
		providerId = provider.id,
		displayName = provider.displayName,
		type = provider.type ?: "OPENAI_COMPATIBLE",
		baseUrl = provider.baseUrl.orEmpty(),
		model = provider.model ?: provider.models.firstOrNull()?.id.orEmpty(),
		apiKey = "",
		contextWindowText = provider.contextWindow.takeIf { it > 0 }?.toString() ?: "0",
	)

/**
 * 后端真实支持的审批策略选项。
 */
fun approvalPolicyOptions(): List<ApprovalPolicyOption> =
	listOf(
		ApprovalPolicyOption("按需询问", "ON_REQUEST", "写文件、命令、补丁和 MCP 工具会触发审批。"),
		ApprovalPolicyOption("全部询问", "ALWAYS", "下一轮所有工具调用都先暂停等待确认。"),
		ApprovalPolicyOption("永不询问", "NEVER", "不安装 HITL 审批 Hook，仍受沙箱和工具拦截器约束。"),
	)

/**
 * 设置页。
 *
 * 该页面现在按 Figma 原型拆成五个真实标签：Provider、权限与审批、本地 MCP、记忆、能力。
 * 每个标签页都只发起对应 JSON-RPC 操作，避免把“看起来切换了”的 UI 状态和后端 Agent 行为割裂。
 */
@Composable
fun SettingsPanel(
	state: AppState,
	onBackToChat: () -> Unit,
	onSelectWorkspace: (String) -> Unit,
	onSelectProvider: (String) -> Unit,
	onCreateProvider: (ProviderSaveParams) -> Unit,
	onUpdateProvider: (ProviderSaveParams) -> Unit,
	onDeleteProvider: (String) -> Unit,
	onTestProvider: (String) -> Unit,
	onSaveSandboxMode: (String) -> Unit,
	onSaveApprovalPolicy: (String) -> Unit,
	onRefreshMcpServer: (String) -> Unit,
	onSaveMemorySettings: (Boolean?, Boolean?, Boolean?, Boolean?) -> Unit,
	onScanMemory: () -> Unit,
	onConsolidateMemory: () -> Unit,
	onSearchMemory: (String) -> Unit,
	onSaveCapabilitySettings: (String, Boolean?, String?) -> Unit,
	onSearchCapabilities: (String) -> Unit,
) {
	var selectedTab by remember { mutableStateOf(SettingsTab.Provider) }

	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(34.dp)
			.verticalScroll(rememberScrollState()),
		verticalArrangement = Arrangement.spacedBy(18.dp),
	) {
		SettingsHeader(onBackToChat = onBackToChat)
		state.settingsState.notice?.let { Text(it, color = BaBiQColors.Success) }
		state.settingsState.error?.let { Text("设置错误: $it", color = BaBiQColors.Danger) }
		if (!state.canEditSettings) {
			Text("当前 turn 运行中，设置会在本轮结束后开放修改。", color = BaBiQColors.Warning)
		}

		WorkspaceSettingsCard(state, onSelectWorkspace)
		SettingsTabs(
			selected = selectedTab,
			onSelect = { selectedTab = it },
		)
		when (selectedTab) {
			SettingsTab.Provider -> ProviderSettingsCard(
				state = state,
				onSelectProvider = onSelectProvider,
				onCreateProvider = onCreateProvider,
				onUpdateProvider = onUpdateProvider,
				onDeleteProvider = onDeleteProvider,
				onTestProvider = onTestProvider,
			)

			SettingsTab.Approval -> PolicySettingsCard(
				state = state,
				onSaveSandboxMode = onSaveSandboxMode,
				onSaveApprovalPolicy = onSaveApprovalPolicy,
			)

			SettingsTab.Mcp -> McpSettingsTab(
				state = state,
				onRefreshMcpServer = onRefreshMcpServer,
			)

			SettingsTab.Memory -> MemorySettingsSection(
				state = state,
				onSaveMemorySettings = onSaveMemorySettings,
				onScanMemory = onScanMemory,
				onConsolidateMemory = onConsolidateMemory,
				onSearchMemory = onSearchMemory,
			)

			SettingsTab.Capability -> CapabilityCenterSection(
				state = state,
				onSaveCapabilitySettings = onSaveCapabilitySettings,
				onSearchCapabilities = onSearchCapabilities,
			)
		}
	}
}

/**
 * 设置页顶部标题栏。
 */
@Composable
private fun SettingsHeader(
	onBackToChat: () -> Unit,
) {
	val model = buildSettingsHeaderModel()
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			model.title,
			style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
		)
		Spacer(Modifier.weight(1f))
		TextButton(onClick = onBackToChat) {
			Text(model.backLabel)
		}
	}
}

/**
 * 标签页切换条。
 */
@Composable
private fun SettingsTabs(
	selected: SettingsTab,
	onSelect: (SettingsTab) -> Unit,
) {
	FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		settingsTabs().forEach { item ->
			if (item.tab == selected) {
				Button(onClick = { onSelect(item.tab) }) { Text(item.label) }
			} else {
				OutlinedButton(onClick = { onSelect(item.tab) }) { Text(item.label) }
			}
		}
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
		Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth()) {
			MetricLine("项目", state.workspace.projectName)
			MetricLine("路径", state.workspace.cwd)
			MetricLine("当前权限", state.workspace.permissionLabel ?: state.workspace.permissionMode ?: "未加载")
		}
		Button(
			enabled = state.canSwitchWorkspace,
			onClick = {
				// 设置页和输入框上下文条共用同一个目录选择器，保证工作目录切换行为一致。
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
	SettingsCard("权限与审批") {
		Text("沙箱权限", fontWeight = FontWeight.Medium)
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			PolicyButton("只读", "READ_ONLY", state.workspace.permissionMode, state.canEditSettings, onSaveSandboxMode)
			PolicyButton("工作区可写", "WORKSPACE_WRITE", state.workspace.permissionMode, state.canEditSettings, onSaveSandboxMode)
			PolicyButton("完全访问", "DANGER_FULL_ACCESS", state.workspace.permissionMode, state.canEditSettings, onSaveSandboxMode)
		}
		Text("审批策略", fontWeight = FontWeight.Medium)
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			val currentApproval = state.settingsState.settings?.approvalPolicy
			approvalPolicyOptions().forEach { option ->
				PolicyButton(option.label, option.value, currentApproval, state.canEditSettings, onSaveApprovalPolicy)
			}
		}
		approvalPolicyOptions().forEach { option ->
			Text("${option.label}: ${option.description}", color = BaBiQColors.Muted, style = MaterialTheme.typography.bodySmall)
		}
		SettingsSubCard("会话内始终允许规则") {
			Text("审批弹窗中的“始终允许”只作用于当前会话、当前工具和参数指纹，不会写成永久全局放行。", color = BaBiQColors.Muted)
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
	onUpdateProvider: (ProviderSaveParams) -> Unit,
	onDeleteProvider: (String) -> Unit,
	onTestProvider: (String) -> Unit,
) {
	var editorMode by remember { mutableStateOf(ProviderEditorMode.None) }
	var draft by remember { mutableStateOf(ProviderEditorState()) }

	LaunchedEffect(state.settingsState.notice) {
		if (state.settingsState.notice?.contains("Provider") == true) {
			editorMode = ProviderEditorMode.None
			draft = ProviderEditorState()
		}
	}

	SettingsCard("Provider") {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			Button(
				enabled = state.canEditSettings,
				onClick = {
					editorMode = ProviderEditorMode.Create
					draft = ProviderEditorState()
				},
			) {
				Text("+ 新增 Provider")
			}
			Text("当前: ${state.providerState.active.label}", color = BaBiQColors.Muted)
		}
		if (editorMode != ProviderEditorMode.None) {
			ProviderForm(
				mode = editorMode,
				draft = draft,
				enabled = state.canEditSettings && !state.settingsState.saving,
				onDraftChange = { draft = it },
				onSubmit = {
					if (editorMode == ProviderEditorMode.Edit) {
						onUpdateProvider(draft.toSaveParams())
					} else {
						onCreateProvider(draft.toSaveParams())
					}
				},
				onCancel = {
					editorMode = ProviderEditorMode.None
					draft = ProviderEditorState()
				},
			)
		}
		if (state.providerState.providers.isEmpty()) {
			Text("当前没有可选择的后端 Provider，请检查后端配置。", color = BaBiQColors.Muted)
		} else {
			ProviderTableHeader()
			state.providerState.providers.forEach { provider ->
				ProviderRow(
					provider = provider,
					enabled = state.canEditSettings,
					onEditProvider = {
						editorMode = ProviderEditorMode.Edit
						draft = providerDraftFrom(provider)
					},
					onSelectProvider = onSelectProvider,
					onDeleteProvider = onDeleteProvider,
					onTestProvider = onTestProvider,
				)
			}
		}
	}
}

private enum class ProviderEditorMode {
	None,
	Create,
	Edit,
}

/**
 * Provider 新增/编辑表单。
 */
@Composable
private fun ProviderForm(
	mode: ProviderEditorMode,
	draft: ProviderEditorState,
	enabled: Boolean,
	onDraftChange: (ProviderEditorState) -> Unit,
	onSubmit: () -> Unit,
	onCancel: () -> Unit,
) {
	SettingsSubCard(if (mode == ProviderEditorMode.Edit) "编辑 Provider" else "新增 Provider") {
		OutlinedTextField(
			value = draft.providerId,
			onValueChange = { onDraftChange(draft.copy(providerId = it)) },
			enabled = enabled && mode == ProviderEditorMode.Create,
			label = { Text("Provider ID") },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
		)
		OutlinedTextField(
			value = draft.displayName,
			onValueChange = { onDraftChange(draft.copy(displayName = it)) },
			enabled = enabled,
			label = { Text("显示名称") },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
		)
		OutlinedTextField(
			value = draft.type,
			onValueChange = { onDraftChange(draft.copy(type = it)) },
			enabled = enabled,
			label = { Text("类型") },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
		)
		OutlinedTextField(
			value = draft.baseUrl,
			onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
			enabled = enabled,
			label = { Text("Base URL") },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
		)
		OutlinedTextField(
			value = draft.model,
			onValueChange = { onDraftChange(draft.copy(model = it)) },
			enabled = enabled,
			label = { Text("模型") },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
		)
		OutlinedTextField(
			value = draft.contextWindowText,
			onValueChange = { onDraftChange(draft.copy(contextWindowText = it)) },
			enabled = enabled,
			label = { Text("上下文窗口") },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
		)
		OutlinedTextField(
			value = draft.apiKey,
			onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
			enabled = enabled,
			label = { Text(if (mode == ProviderEditorMode.Edit) "API Key（留空沿用已有密钥）" else "API Key") },
			visualTransformation = PasswordVisualTransformation(),
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(enabled = enabled, onClick = onSubmit) {
				Text(if (mode == ProviderEditorMode.Edit) "保存修改" else "保存")
			}
			TextButton(onClick = onCancel) {
				Text("取消")
			}
		}
	}
}

@Composable
private fun ProviderTableHeader() {
	Row(
		modifier = Modifier.fillMaxWidth().background(BaBiQColors.Background, RoundedCornerShape(6.dp)).padding(8.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text("名称", modifier = Modifier.weight(1.3f), fontWeight = FontWeight.Bold)
		Text("类型 / 模型", modifier = Modifier.weight(1.6f), fontWeight = FontWeight.Bold)
		Text("上下文", modifier = Modifier.weight(0.8f), fontWeight = FontWeight.Bold)
		Text("操作", modifier = Modifier.weight(1.8f), fontWeight = FontWeight.Bold)
	}
}

/**
 * 单个 Provider 展示行。
 */
@Composable
private fun ProviderRow(
	provider: ProviderInfo,
	enabled: Boolean,
	onEditProvider: () -> Unit,
	onSelectProvider: (String) -> Unit,
	onDeleteProvider: (String) -> Unit,
	onTestProvider: (String) -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(modifier = Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(provider.displayName, fontWeight = FontWeight.Medium)
				if (provider.active) {
					Text("当前", color = BaBiQColors.Success)
				}
			}
			Text(provider.id, color = BaBiQColors.Muted, style = MaterialTheme.typography.labelSmall)
		}
		Column(modifier = Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(provider.type ?: "未配置", color = BaBiQColors.Muted, style = MaterialTheme.typography.labelSmall)
			Text(provider.model ?: provider.models.firstOrNull()?.label ?: "未配置模型")
		}
		Text(
			if (provider.contextWindow > 0) provider.contextWindow.toString() else "默认",
			modifier = Modifier.weight(0.8f),
			color = BaBiQColors.Muted,
		)
		FlowRow(
			modifier = Modifier.weight(1.8f),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalArrangement = Arrangement.spacedBy(6.dp),
		) {
			OutlinedButton(enabled = enabled, onClick = { onSelectProvider(provider.id) }) { Text("设为当前") }
			OutlinedButton(enabled = enabled, onClick = onEditProvider) { Text("编辑") }
			OutlinedButton(enabled = enabled, onClick = { onTestProvider(provider.id) }) { Text("测试") }
			TextButton(enabled = enabled, onClick = { onDeleteProvider(provider.id) }) { Text("删除") }
		}
	}
	HorizontalDivider(color = BaBiQColors.Border)
}

/**
 * 本地 MCP 标签页，作为设置工作台的一部分展示，不再单独占据一个完整产品页面。
 */
@Composable
private fun McpSettingsTab(
	state: AppState,
	onRefreshMcpServer: (String) -> Unit,
) {
	SettingsCard("本地 MCP") {
		Text("展示后端受信配置中的 stdio MCP server 和工具目录。新增或修改 server 仍在后端配置中完成。", color = BaBiQColors.Muted)
		state.mcpState.notice?.let { Text(it, color = BaBiQColors.Success) }
		state.mcpState.error?.let { Text("MCP 错误: $it", color = BaBiQColors.Danger) }
		when {
			state.mcpState.loading -> Text("正在读取 MCP 状态...", color = BaBiQColors.Muted)
			state.mcpState.servers.isEmpty() -> Text("当前没有已配置的 MCP server。", color = BaBiQColors.Muted)
			else -> state.mcpState.servers.forEach { server ->
				InlineMcpServerCard(
					server = server,
					tools = state.mcpState.toolsByServer[server.serverId].orEmpty(),
					refreshing = state.mcpState.refreshingServerId == server.serverId,
					onRefreshMcpServer = onRefreshMcpServer,
				)
			}
		}
	}
}

@Composable
private fun InlineMcpServerCard(
	server: McpServerInfo,
	tools: List<McpToolInfo>,
	refreshing: Boolean,
	onRefreshMcpServer: (String) -> Unit,
) {
	SettingsSubCard(server.displayName) {
		Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(server.serverId, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = BaBiQColors.Muted)
			Text(mcpStatusLabel(server.status), color = mcpStatusColor(server.status))
			Text("${server.toolCount} 工具", color = BaBiQColors.Muted)
			OutlinedButton(enabled = !refreshing, onClick = { onRefreshMcpServer(server.serverId) }) {
				Text(if (refreshing) "刷新中..." else "刷新连接")
			}
		}
		server.lastError?.let { Text(it, color = BaBiQColors.Danger) }
		if (tools.isEmpty()) {
			Text("暂无工具", color = BaBiQColors.Muted)
		} else {
			tools.take(8).forEach { tool ->
				Text(tool.namespacedName, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
				if (tool.description.isNotBlank()) {
					Text(tool.description.take(120), color = BaBiQColors.Muted)
				}
			}
		}
	}
}

private fun mcpStatusLabel(status: String): String =
	when (status) {
		"connected" -> "已连接"
		"configured" -> "已配置"
		"failed" -> "连接失败"
		"disabled" -> "未启用"
		else -> status
	}

private fun mcpStatusColor(status: String) =
	when (status) {
		"connected" -> BaBiQColors.Success
		"failed" -> BaBiQColors.Danger
		else -> BaBiQColors.Muted
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
		Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
			content()
		}
	}
}

/**
 * 设置页内部的小卡片，用于表单、说明和详情。
 */
@Composable
private fun SettingsSubCard(
	title: String,
	content: @Composable ColumnScope.() -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(BaBiQColors.Background, RoundedCornerShape(8.dp))
			.padding(12.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(title, fontWeight = FontWeight.Bold)
		content()
	}
}
