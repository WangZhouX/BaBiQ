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

@Composable
fun ProviderSelector(
	providerState: ProviderState,
	onSelectProvider: (String, String?) -> Unit,
) {
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
					DropdownMenuItem(
						text = { Text(provider.label) },
						onClick = {
							expanded = false
							onSelectProvider(provider.id, null)
						},
					)
				} else {
					provider.models.forEach { model ->
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
