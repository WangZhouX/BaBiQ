package com.wzx.huitai.desktop.ui.window

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import com.wzx.huitai.desktop.ui.brand.BusinessBrandResources
import java.awt.Dimension
import java.awt.Frame
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** 翔鸟律智桌面端唯一的窗口尺寸与原生品牌规格。 */
object BusinessDesktopWindowSpec {
    const val title: String = "翔鸟律智桌面端"
    const val iconResourcePath: String = BusinessBrandResources.LOGO_PATH

    val initialPlacement: WindowPlacement = WindowPlacement.Maximized
    val initialAwtExtendedState: Int = Frame.MAXIMIZED_BOTH
    val restoredWidth: Dp = 1440.dp
    val restoredHeight: Dp = 900.dp
    val minimumWidth: Dp = 1100.dp
    val minimumHeight: Dp = 720.dp

    fun iconPainter(): Painter = BitmapPainter(BusinessBrandResources.logoImageBitmap())

    /**
     * Compose Desktop 会把 WindowState 的 Dp 数值直接映射到 AWT 用户空间；
     * Windows HiDPI 的物理像素缩放由 AWT 处理，这里不能再乘 LocalDensity。
     */
    fun minimumSizeAwtUserSpace(): Dimension = Dimension(
        minimumWidth.value.roundToInt(),
        minimumHeight.value.roundToInt(),
    )

    fun applyNativeBranding(window: ComposeWindow) {
        window.minimumSize = minimumSizeAwtUserSpace()
        window.iconImage = requireNotNull(
            ImageIO.read(ByteArrayInputStream(BusinessBrandResources.logoBytes())),
        ) { "Unable to decode the packaged Xiangniao window icon" }
        window.extendedState = initialAwtExtendedState
    }
}
