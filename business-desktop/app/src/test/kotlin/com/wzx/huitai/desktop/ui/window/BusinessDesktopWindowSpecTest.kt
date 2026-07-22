package com.wzx.huitai.desktop.ui.window

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import com.wzx.huitai.desktop.ui.brand.BusinessBrandResources
import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals

class BusinessDesktopWindowSpecTest {
    @Test
    fun `window uses the Xiangniao product identity and starts maximized`() {
        assertEquals("翔鸟律智桌面端", BusinessDesktopWindowSpec.title)
        assertEquals(WindowPlacement.Maximized, BusinessDesktopWindowSpec.initialPlacement)
        assertEquals(BusinessBrandResources.LOGO_PATH, BusinessDesktopWindowSpec.iconResourcePath)
        assertEquals(Frame.MAXIMIZED_BOTH, BusinessDesktopWindowSpec.initialAwtExtendedState)
    }

    @Test
    fun `window keeps the designed restore and minimum dp sizes`() {
        assertEquals(1440.dp, BusinessDesktopWindowSpec.restoredWidth)
        assertEquals(900.dp, BusinessDesktopWindowSpec.restoredHeight)
        assertEquals(1100.dp, BusinessDesktopWindowSpec.minimumWidth)
        assertEquals(720.dp, BusinessDesktopWindowSpec.minimumHeight)
    }

    @Test
    fun `minimum AWT size remains in logical user space under HiDPI`() {
        assertEquals(
            java.awt.Dimension(1100, 720),
            BusinessDesktopWindowSpec.minimumSizeAwtUserSpace(),
        )
    }
}
