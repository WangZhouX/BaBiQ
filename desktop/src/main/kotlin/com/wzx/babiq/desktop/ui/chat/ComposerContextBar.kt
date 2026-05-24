package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.ui.common.StatusBadge
import com.wzx.babiq.desktop.ui.common.chooseWorkspaceDirectory

/**
 * 输入框上下文条。
 *
 * 它只展示当前已经接入真实数据的上下文：工作目录和模型。
 * 分支、权限模式等扩展信息待后续真正接入后再恢复，避免展示死数据。
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
		ProviderSelector(
			providerState = state.providerState,
			onSelectProvider = onSelectProvider,
		)
	}
}
