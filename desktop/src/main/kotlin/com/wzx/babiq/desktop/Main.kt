package com.wzx.babiq.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.wzx.babiq.desktop.app.BaBiQDesktopApp
import com.wzx.babiq.desktop.generated.resources.Res
import com.wzx.babiq.desktop.generated.resources.babiq_window_icon
import com.wzx.babiq.desktop.runtime.DesktopRuntimeLauncher
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.Path
import org.jetbrains.compose.resources.painterResource

internal val DefaultWindowSize = DpSize(1400.dp, 860.dp)

fun main() {
	try {
		val runtimeSession = DesktopRuntimeLauncher().start()
		try {
			application {
				Window(
					title = "BaBiQ",
					icon = painterResource(Res.drawable.babiq_window_icon),
					state = WindowState(size = DefaultWindowSize),
					onCloseRequest = {
						runtimeSession.close()
						exitApplication()
					},
				) {
					BaBiQDesktopApp(runtimeSession.config)
				}
			}
		} finally {
			runtimeSession.close()
		}
	} catch (throwable: Throwable) {
		logDesktopStartupFailure(throwable)
		throw throwable
	}
}

private fun logDesktopStartupFailure(throwable: Throwable) {
	try {
		val logDir = Path(System.getProperty("user.home")).resolve(".babiq").resolve("logs")
		Files.createDirectories(logDir)
		Files.newBufferedWriter(
			logDir.resolve("desktop-startup.log"),
			StandardOpenOption.CREATE,
			StandardOpenOption.APPEND,
		).use { writer ->
			writer.write("${Instant.now()} BaBiQ desktop startup failed")
			writer.newLine()
			throwable.printStackTrace(PrintWriter(writer))
			writer.newLine()
		}
	} catch (_: Throwable) {
	}
}
