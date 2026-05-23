package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import com.wzx.babiq.desktop.state.ProviderState
import com.wzx.babiq.desktop.ui.common.BadgeTone
import com.wzx.babiq.desktop.ui.common.StatusBadge

/**
 * Provider/Model 下拉选择器。
 *
 * UI 展示的是模型标签；真正生效逻辑在 Controller 中调用后端 `model/providers/set-active`。
 */
@Composable
fun ProviderSelector(
	providerState: ProviderState,
	onSelectProvider: (String, String?) -> Unit,
) {
	// expanded 是纯 UI 状态，只控制下拉菜单开关，不进入 AppState。
	var expanded by remember { mutableStateOf(false) }
	Column {
		StatusBadge(
			text = "模型 ${providerState.active.label}",
			tone = BadgeTone.Info,
			modifier = Modifier.clickable(enabled = providerState.providers.isNotEmpty()) {
				expanded = true
			},
		)
		DropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
		) {
			providerState.providers.forEach { provider ->
				if (provider.models.isEmpty()) {
					// 某些 provider 可能暂时不返回模型列表，这时仍允许按 provider 粒度选择。
					DropdownMenuItem(
						text = { Text(provider.label) },
						onClick = {
							expanded = false
							onSelectProvider(provider.id, null)
						},
					)
				} else {
					provider.models.forEach { model ->
						// provider + model 组合显示，避免不同供应商下模型同名时看不清来源。
						DropdownMenuItem(
							text = {
								Row {
									Text(provider.label)
									Spacer(Modifier.width(8.dp))
									Text(model.label)
								}
							},
							onClick = {
								expanded = false
								onSelectProvider(provider.id, model.id)
							},
						)
					}
				}
			}
		}
	}
}
