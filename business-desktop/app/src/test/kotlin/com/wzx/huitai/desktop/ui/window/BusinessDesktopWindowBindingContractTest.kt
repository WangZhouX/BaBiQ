package com.wzx.huitai.desktop.ui.window

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessDesktopWindowBindingContractTest {
    @Test
    fun `Main consumes the canonical window spec and applies native branding`() {
        val source = Path.of(
            "src",
            "main",
            "kotlin",
            "com",
            "wzx",
            "huitai",
            "desktop",
            "Main.kt",
        ).toFile().readText()

        assertTrue(source.contains("title = BusinessDesktopWindowSpec.title"))
        assertTrue(source.contains("placement = BusinessDesktopWindowSpec.initialPlacement"))
        assertTrue(source.contains("width = BusinessDesktopWindowSpec.restoredWidth"))
        assertTrue(source.contains("height = BusinessDesktopWindowSpec.restoredHeight"))
        assertTrue(source.contains("icon = BusinessDesktopWindowSpec.iconPainter()"))
        assertTrue(source.contains("BusinessDesktopWindowSpec.applyNativeBranding(window)"))
        assertFalse(source.contains("undecorated = true"))
        assertFalse(source.contains("transparent = true"))
    }

    @Test
    fun `Main owns collapsed assistant state and preserves requested dock width`() {
        val source = Path.of(
            "src",
            "main",
            "kotlin",
            "com",
            "wzx",
            "huitai",
            "desktop",
            "Main.kt",
        ).toFile().readText()

        assertTrue(source.contains("var assistantExpanded by remember { mutableStateOf(false) }"))
        assertTrue(
            source.contains(
                "var requestedAssistantWidth by remember { mutableStateOf(BusinessDesktopLayoutPolicy.defaultAssistantWidth) }",
            ),
        )
        assertTrue(source.contains("agentPanelExpanded = assistantExpanded"))
        assertTrue(source.contains("requestedAssistantWidth = requestedAssistantWidth"))
        assertTrue(source.contains("onAgentPanelExpandedChange = { assistantExpanded = it }"))
        assertTrue(source.contains("onRequestedAssistantWidthChange = { requestedAssistantWidth = it }"))
    }
}
