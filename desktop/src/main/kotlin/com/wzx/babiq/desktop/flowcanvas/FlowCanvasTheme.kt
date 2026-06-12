package com.wzx.babiq.desktop.flowcanvas

import androidx.compose.ui.graphics.Color

data class FlowCanvasPalette(
	val background: Color = Color(0xFFFAFAFA),
	val grid: Color = Color(0xFFEDEFF2),
	val edge: Color = Color(0xFFB8C0CC),
	val nodeBackground: Color = Color.White,
	val nodeBorder: Color = Color(0xFFD7DCE3),
	val selectedBorder: Color = Color(0xFF246BCE),
	val text: Color = Color(0xFF20242A),
	val muted: Color = Color(0xFF657080),
	val insertBackground: Color = Color(0xFFFFEFEF),
	val readOnlyDot: Color = Color(0xFF64748B),
	val workspaceDot: Color = Color(0xFFD97706),
	val running: Color = Color(0xFF2563EB),
	val completed: Color = Color(0xFF16A34A),
	val failed: Color = Color(0xFFDC2626),
)
