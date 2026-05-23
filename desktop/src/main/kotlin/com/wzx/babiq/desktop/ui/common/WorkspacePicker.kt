package com.wzx.babiq.desktop.ui.common

import java.io.File
import javax.swing.JFileChooser

/**
 * 使用 JDK 自带的 Swing 目录选择器，避免为了一个桌面端文件夹选择能力引入额外依赖。
 *
 * Compose Desktop 本身运行在 JVM 桌面环境中，官方也支持与 Swing 互操作；这里把 Swing 细节
 * 收敛到一个很小的函数里，UI 组件只拿到最终目录字符串，后续如果换成更原生的选择器也不影响状态层。
 */
fun chooseWorkspaceDirectory(initialDirectory: String): String? {
	val chooser = JFileChooser(initialDirectory.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"))
	chooser.dialogTitle = "选择工作目录"
	chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
	chooser.isAcceptAllFileFilterUsed = false
	val result = chooser.showOpenDialog(null)
	return chooser.selectedFile
		?.takeIf { result == JFileChooser.APPROVE_OPTION && it.isDirectory }
		?.let(File::getAbsolutePath)
}
