package com.wzx.babiq.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication

fun main() = singleWindowApplication(
	title = "BaBiQ Desktop",
	state = WindowState(size = DpSize(900.dp, 700.dp))
) {
	App()
}

@Composable
fun App() {
	MaterialTheme {
		Surface(Modifier.fillMaxSize()) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text("BaBiQ Desktop - P1-0 skeleton OK")
			}
		}
	}
}
