package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * 集中加载从 Web 项目迁移的工作台原始位图。
 *
 * 位图只在首次使用时解码，避免每次重组重复读取资源；Compose 端不访问 OA 的远程图片
 * URL，从而继续保持本地 BFF 的安全边界。
 */
internal object BusinessWorkbenchAssets {
    const val SCHEDULE_ICON_PATH = "/brand/workbench/icon_schedule.png"
    const val SCHEDULE_EMPTY_PATH = "/brand/workbench/icon_empty_schedule.webp"
    const val STATISTIC_BACKGROUND_PATH = "/brand/workbench/icon_home_datebg.webp"
    const val PROFILE_BACKGROUND_PATH = "/brand/workbench/icon_home_potbg.webp"
    const val CONFLICT_ENTRANCE_PATH = "/brand/workbench/icon_honework_entrance1.png"
    const val CASE_ENTRANCE_PATH = "/brand/workbench/icon_honework_entrance2.png"
    const val CUSTOMER_ENTRANCE_PATH = "/brand/workbench/icon_honework_entrance3.png"

    private val decoded = mutableMapOf<String, ImageBitmap>()

    fun scheduleIconImage(): ImageBitmap = image(SCHEDULE_ICON_PATH)

    fun scheduleEmptyImage(): ImageBitmap = image(SCHEDULE_EMPTY_PATH)

    fun statisticBackgroundImage(): ImageBitmap = image(STATISTIC_BACKGROUND_PATH)

    fun profileBackgroundImage(): ImageBitmap = image(PROFILE_BACKGROUND_PATH)

    fun quickEntranceImage(configCode: String?, path: String?): ImageBitmap? = when {
        configCode == "conflict_check" -> image(CONFLICT_ENTRANCE_PATH)
        configCode == "case_application" || path == "/case" -> image(CASE_ENTRANCE_PATH)
        configCode == "new_customer" || path == "/customer" -> image(CUSTOMER_ENTRANCE_PATH)
        else -> null
    }

    internal fun read(path: String): ByteArray =
        requireNotNull(BusinessWorkbenchAssets::class.java.getResourceAsStream(path)) {
            "Missing packaged workbench resource: $path"
        }.use { it.readBytes() }

    private fun image(path: String): ImageBitmap = synchronized(decoded) {
        decoded.getOrPut(path) { decode(read(path)) }
    }

    private fun decode(bytes: ByteArray): ImageBitmap =
        Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
}
