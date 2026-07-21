package com.wzx.huitai.desktop.ui.brand

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

object BusinessBrandResources {
    const val LOGO_PATH: String = "/brand/xiangniao-logo.png"
    const val MASCOT_PATH: String = "/brand/xiaolv-mascot.png"
    const val WINDOWS_ICON_PATH: String = "/brand/xiangniao.ico"

    fun logoBytes(): ByteArray = readResource(LOGO_PATH)

    fun mascotBytes(): ByteArray = readResource(MASCOT_PATH)

    fun windowsIconBytes(): ByteArray = readResource(WINDOWS_ICON_PATH)

    fun logoImageBitmap(): ImageBitmap = decodeImage(logoBytes(), LOGO_PATH)

    fun mascotImageBitmap(): ImageBitmap = decodeImage(mascotBytes(), MASCOT_PATH)

    private fun readResource(path: String): ByteArray =
        requireNotNull(BusinessBrandResources::class.java.getResourceAsStream(path)) {
            "Missing packaged brand resource: $path"
        }.use { it.readBytes() }

    private fun decodeImage(bytes: ByteArray, path: String): ImageBitmap =
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }
            .getOrElse { cause ->
                throw IllegalArgumentException("Invalid packaged brand image: $path", cause)
            }
}
