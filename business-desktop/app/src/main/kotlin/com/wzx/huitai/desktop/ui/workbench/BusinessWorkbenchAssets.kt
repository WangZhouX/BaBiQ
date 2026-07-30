package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

internal object BusinessWorkbenchAssets {
    const val SCHEDULE_ICON_PATH = "/brand/workbench/icon_schedule.png"
    const val SCHEDULE_EMPTY_PATH = "/brand/workbench/icon_empty_schedule.webp"

    private val scheduleIcon by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        decode(read(SCHEDULE_ICON_PATH))
    }
    private val scheduleEmpty by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        decode(read(SCHEDULE_EMPTY_PATH))
    }

    fun scheduleIconImage(): ImageBitmap = scheduleIcon

    fun scheduleEmptyImage(): ImageBitmap = scheduleEmpty

    internal fun read(path: String): ByteArray =
        requireNotNull(BusinessWorkbenchAssets::class.java.getResourceAsStream(path)) {
            "Missing packaged workbench resource: $path"
        }.use { it.readBytes() }

    private fun decode(bytes: ByteArray): ImageBitmap =
        Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
}
