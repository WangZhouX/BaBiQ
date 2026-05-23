package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.ui.common.BadgeTone
import com.wzx.babiq.desktop.ui.common.StatusBadge
import com.wzx.babiq.desktop.ui.common.chooseWorkspaceDirectory

/**
 * 输入框上下文条。
 *
 * 它展示本轮任务将使用的执行边界：工作目录、模式、分支、worktree、权限和模型。
 * P1-4 中只有目录和模型是真实可切换能力，其余先作为上下文展示。
 */
@Composable
fun ComposerContextBar(
	state: AppState,
	onSelectWorkspace: (String) -> Unit,
	onSelectProvider: (String, String?) -> Unit,
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
		StatusBadge(state.workspace.mode)
		StatusBadge("⑂ ${state.workspace.branch}")
		StatusBadge(state.workspace.worktree)
		StatusBadge(state.workspace.permission, BadgeTone.Warning)
		ProviderSelector(
			providerState = state.providerState,
			onSelectProvider = onSelectProvider,
		)
	}
}
