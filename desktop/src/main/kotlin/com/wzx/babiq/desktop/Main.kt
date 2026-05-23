package com.wzx.babiq.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import com.wzx.babiq.desktop.app.BaBiQDesktopApp

fun main() = singleWindowApplication(
	title = "BaBiQ",
	state = WindowState(size = DpSize(1180.dp, 780.dp)),
) {
	BaBiQDesktopApp()
}
