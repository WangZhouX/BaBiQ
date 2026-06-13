package com.wzx.babiq.desktop.flowcanvas

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowCanvasPortabilityTest {

	@Test
	fun `flow canvas package does not import app protocol state or theme packages`() {
		val root = Path.of("src/main/kotlin/com/wzx/babiq/desktop/flowcanvas")
		val files = Files.walk(root)
			.filter { it.extension == "kt" }
			.toList()

		assertTrue(files.isNotEmpty())
		files.forEach { file ->
			val text = file.readText()
			assertFalse(text.contains("com.wzx.babiq.desktop.protocol"), "$file imports protocol")
			assertFalse(text.contains("com.wzx.babiq.desktop.state"), "$file imports state")
			assertFalse(text.contains("com.wzx.babiq.desktop.ui.theme"), "$file imports app theme")
		}
	}
}
