package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.CapabilityUiState
import com.wzx.babiq.desktop.state.ContextWindowUiState
import com.wzx.babiq.desktop.state.MemoryUiState
import com.wzx.babiq.desktop.ui.common.BadgeTone
import com.wzx.babiq.desktop.ui.common.StatusBadge
import com.wzx.babiq.desktop.ui.common.chooseWorkspaceDirectory

/**
 * 输入框上下文条。
 *
 * 它只展示当前已经接入真实数据的上下文：工作目录、后端沙箱权限和模型。
 * 分支等扩展信息待后续真正接入后再恢复，避免展示死数据。
 */
@Composable
fun ComposerContextBar(
	state: AppState,
	onSelectWorkspace: (String) -> Unit,
	onSelectProvider: (String, String?) -> Unit,
	onChangeSandboxMode: ((String) -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	FlowRow(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		StatusBadge(
			text = "目录 ${state.workspace.projectName}",
			modifier = Modifier.clickable(enabled = state.canSwitchWorkspace) {
				// 选择目录后只更新下一轮上下文；运行中的 turn 不会被中途改 cwd。
				chooseWorkspaceDirectory(state.workspace.cwd)?.let(onSelectWorkspace)
			},
		)
		state.workspace.permissionLabel?.let { label ->
			// 权限来自后端 sandbox/policy；点击后写回设置服务，下一轮 turn 会读取新的运行策略快照。
			SandboxModeSelector(
				label = label,
				currentMode = state.workspace.permissionMode,
				canEditSettings = state.canEditSettings,
				onChangeSandboxMode = onChangeSandboxMode,
			)
		}
		StatusBadge(
			text = contextWindowChipLabel(state.contextWindowState),
			tone = contextWindowChipTone(state.contextWindowState),
		)
		StatusBadge(
			text = memoryChipLabel(state.memoryState),
			tone = memoryChipTone(state.memoryState),
		)
		StatusBadge(
			text = capabilityChipLabel(state.capabilityState),
			tone = capabilityChipTone(state.capabilityState),
		)
		ProviderSelector(
			providerState = state.providerState,
			onSelectProvider = onSelectProvider,
		)
	}
}

/**
 * 输入栏权限菜单中的一个选项。
 *
 * @property mode 后端 `SandboxMode` 枚举名，提交给 `sandbox/policy/set`。
 * @property label 展示给用户看的中文权限名称，和后端查询接口保持一致。
 */
internal data class SandboxModeMenuOption(
	val mode: String,
	val label: String,
)

/**
 * 聊天输入栏允许快速切换的沙箱权限。
 *
 * 这里刻意只放后端已经稳定支持的三种模式，避免 UI 出现后端无法识别的值。
 */
internal val sandboxModeMenuOptions = listOf(
	SandboxModeMenuOption("READ_ONLY", "只读权限"),
	SandboxModeMenuOption("WORKSPACE_WRITE", "工作区可写"),
	SandboxModeMenuOption("DANGER_FULL_ACCESS", "完全访问权限"),
)

/**
 * 判断聊天页权限 chip 是否应该响应点击。
 *
 * 运行中 turn 使用启动时策略快照，切换设置只影响下一轮；因此运行中禁用，避免用户误会当前工具调用会被中途放权。
 */
internal fun canOpenSandboxModeMenu(
	canEditSettings: Boolean,
	onChangeSandboxMode: ((String) -> Unit)?,
): Boolean = canEditSettings && onChangeSandboxMode != null

/**
 * 输入框上下文窗口 chip 文案。
 *
 * 它只展示摘要，不展开完整快照；完整快照留给运行详情，避免底部输入栏被审计信息撑开。
 */
internal fun contextWindowChipLabel(state: ContextWindowUiState): String {
	if (state.loading) {
		return "上下文 读取中"
	}
	if (state.error != null) {
		return "上下文 异常"
	}
	val status = state.status ?: return "上下文 未生成"
	if (status.lastSnapshotId == null) {
		return "上下文 未生成"
	}
	if (status.compactionCount > 0) {
		return "已压缩 ${status.compactionCount} 次"
	}
	if (status.modelContextWindow <= 0) {
		return "上下文 ${status.lastEstimatedTokens} token"
	}
	return "上下文 ${(status.usageRatio * 100).toInt().coerceAtLeast(0)}%"
}

/**
 * 上下文窗口 chip 色调。
 *
 * over_threshold 代表后端已经判断接近或超过阈值；UI 用 Warning 提醒用户后续可能触发压缩。
 */
internal fun contextWindowChipTone(state: ContextWindowUiState): BadgeTone {
	val status = state.status ?: return if (state.error != null) BadgeTone.Danger else BadgeTone.Info
	return when {
		state.loading -> BadgeTone.Info
		state.error != null -> BadgeTone.Danger
		status.lastCompactionStatus == "FAILED" -> BadgeTone.Warning
		status.compactionCount > 0 -> BadgeTone.Success
		status.status == "over_threshold" || status.usageRatio >= 0.8 -> BadgeTone.Warning
		status.lastSnapshotId != null -> BadgeTone.Success
		else -> BadgeTone.Info
	}
}

/**
 * 长期记忆 chip 文案。
 *
 * 该 chip 展示的是后端 read/generate 开关和候选状态，不代表聊天消息里已经出现了长期记忆正文；
 * 正文注入仍由后端上下文窗口组装器按 token 预算完成。
 */
internal fun memoryChipLabel(state: MemoryUiState): String {
	if (state.loading) {
		return "长期记忆 读取中"
	}
	if (state.error != null) {
		return "长期记忆 异常"
	}
	val status = state.status ?: return "长期记忆 未加载"
	if (!status.enabled) {
		return "长期记忆 已关闭"
	}
	if (!status.readEnabled) {
		return "长期记忆 不注入"
	}
	return "长期记忆 G${status.phase2Generation}"
}

/**
 * 长期记忆 chip 色调。
 */
internal fun memoryChipTone(state: MemoryUiState): BadgeTone {
	val status = state.status ?: return if (state.error != null) BadgeTone.Danger else BadgeTone.Info
	return when {
		state.loading -> BadgeTone.Info
		state.error != null -> BadgeTone.Danger
		!status.enabled || !status.readEnabled -> BadgeTone.Warning
		status.lastSummaryArtifactId != null -> BadgeTone.Success
		status.cleanCandidateCount > 0 -> BadgeTone.Warning
		else -> BadgeTone.Info
	}
}

/**
 * 能力按需装配 chip 文案。
 */
internal fun capabilityChipLabel(state: CapabilityUiState): String {
	if (state.loading) {
		return "能力 读取中"
	}
	if (state.error != null) {
		return "能力 异常"
	}
	val status = state.status ?: return "能力 未加载"
	return "能力 常驻${status.visibleCount}/按需${status.deferredCount}"
}

/**
 * 能力按需装配 chip 色调。
 */
internal fun capabilityChipTone(state: CapabilityUiState): BadgeTone {
	val status = state.status ?: return if (state.error != null) BadgeTone.Danger else BadgeTone.Info
	return when {
		state.loading -> BadgeTone.Info
		state.error != null -> BadgeTone.Danger
		status.deferredCount > 0 -> BadgeTone.Success
		status.enabledCount > 0 -> BadgeTone.Info
		else -> BadgeTone.Warning
	}
}

/**
 * 权限 chip 下拉菜单。
 */
@Composable
private fun SandboxModeSelector(
	label: String,
	currentMode: String?,
	canEditSettings: Boolean,
	onChangeSandboxMode: ((String) -> Unit)?,
) {
	var expanded by remember { mutableStateOf(false) }
	val enabled = canOpenSandboxModeMenu(canEditSettings, onChangeSandboxMode)
	Column {
		StatusBadge(
			text = label,
			tone = BadgeTone.Warning,
			modifier = Modifier.clickable(enabled = enabled) {
				expanded = true
			},
		)
		DropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
		) {
			sandboxModeMenuOptions.forEach { option ->
				DropdownMenuItem(
					text = {
						val suffix = if (option.mode == currentMode) "（当前）" else ""
						Text("${option.label}$suffix")
					},
					onClick = {
						expanded = false
						onChangeSandboxMode?.invoke(option.mode)
					},
				)
			}
		}
	}
}
