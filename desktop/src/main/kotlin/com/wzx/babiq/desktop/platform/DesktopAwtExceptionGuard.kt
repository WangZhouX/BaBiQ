package com.wzx.babiq.desktop.platform

import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 收口 Windows 剪贴板被短暂占用时 Compose Desktop 选区复制抛出的 AWT 异常。
 */
internal object DesktopAwtExceptionGuard {
	private const val CLIPBOARD_OPEN_FAILURE = "cannot open system clipboard"
	private val installed = AtomicBoolean(false)

	fun install() {
		if (installed.compareAndSet(false, true)) {
			Toolkit.getDefaultToolkit().systemEventQueue.push(GuardedEventQueue())
		}
	}

	internal fun shouldSuppress(throwable: Throwable): Boolean =
		generateSequence(throwable) { it.cause }.any(::isClipboardOpenFailure)

	private fun isClipboardOpenFailure(throwable: Throwable): Boolean {
		if (throwable !is IllegalStateException || throwable.message != CLIPBOARD_OPEN_FAILURE) {
			return false
		}
		return throwable.stackTrace.any { frame ->
			frame.className == "sun.awt.windows.WClipboard" ||
				frame.className == "androidx.compose.ui.platform.AwtPlatformClipboard"
		}
	}

	private class GuardedEventQueue : EventQueue() {
		override fun dispatchEvent(event: AWTEvent) {
			try {
				super.dispatchEvent(event)
			} catch (throwable: Throwable) {
				if (!shouldSuppress(throwable)) {
					throw throwable
				}
			}
		}
	}
}
