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
