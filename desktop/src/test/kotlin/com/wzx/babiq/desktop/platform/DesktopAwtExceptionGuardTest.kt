package com.wzx.babiq.desktop.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAwtExceptionGuardTest {
	@Test
	fun `识别 Compose 选区复制触发的 Windows 剪贴板占用异常`() {
		val throwable = clipboardOpenFailure()

		assertTrue(DesktopAwtExceptionGuard.shouldSuppress(throwable))
	}

	@Test
	fun `识别被包装后的剪贴板占用异常`() {
		val throwable = RuntimeException("dispatch failed", clipboardOpenFailure())

		assertTrue(DesktopAwtExceptionGuard.shouldSuppress(throwable))
	}

	@Test
	fun `不吞掉其他桌面端异常`() {
		assertFalse(DesktopAwtExceptionGuard.shouldSuppress(IllegalStateException("other failure")))
		assertFalse(DesktopAwtExceptionGuard.shouldSuppress(clipboardOpenFailureFromOtherStack()))
	}

	private fun clipboardOpenFailure(): IllegalStateException =
		IllegalStateException("cannot open system clipboard").also { throwable ->
			throwable.stackTrace = arrayOf(
				StackTraceElement("sun.awt.windows.WClipboard", "openClipboard", "WClipboard.java", 0),
				StackTraceElement("androidx.compose.ui.platform.AwtPlatformClipboard", "setClipEntry", "PlatformClipboard.desktop.kt", 87),
			)
		}

	private fun clipboardOpenFailureFromOtherStack(): IllegalStateException =
		IllegalStateException("cannot open system clipboard").also { throwable ->
			throwable.stackTrace = arrayOf(
				StackTraceElement("com.wzx.babiq.desktop.Other", "copy", "Other.kt", 12),
			)
		}
}
