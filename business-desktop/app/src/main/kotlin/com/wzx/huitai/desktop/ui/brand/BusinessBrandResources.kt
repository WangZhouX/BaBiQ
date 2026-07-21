package com.wzx.huitai.desktop.ui.brand

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

object BusinessBrandResources {
    const val LOGO_PATH: String = "/brand/xiangniao-logo.png"
    const val MASCOT_PATH: String = "/brand/xiaolv-mascot.png"
    const val WINDOWS_ICON_PATH: String = "/brand/xiangniao.ico"

    private val cachedLogoImageBitmap: ImageBitmap by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        decodeImage(logoBytes(), LOGO_PATH)
    }
    private val cachedMascotImageBitmap: ImageBitmap by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        decodeImage(mascotBytes(), MASCOT_PATH)
    }

    fun logoBytes(): ByteArray = readResource(LOGO_PATH)

    fun mascotBytes(): ByteArray = readResource(MASCOT_PATH)

    fun windowsIconBytes(): ByteArray = readResource(WINDOWS_ICON_PATH)

    fun logoImageBitmap(): ImageBitmap = cachedLogoImageBitmap

    fun mascotImageBitmap(): ImageBitmap = cachedMascotImageBitmap

    private fun readResource(path: String): ByteArray =
        requireNotNull(BusinessBrandResources::class.java.getResourceAsStream(path)) {
            "Missing packaged brand resource: $path"
        }.use { it.readBytes() }

    internal fun decodeImage(
        bytes: ByteArray,
        path: String,
        decoder: (ByteArray) -> ImageBitmap = ::decodeSkiaImage,
    ): ImageBitmap = try {
        decoder(bytes)
    } catch (cause: Exception) {
        throw IllegalArgumentException("Invalid packaged brand image: $path", cause)
    }

    private fun decodeSkiaImage(bytes: ByteArray): ImageBitmap =
        Image.makeFromEncoded(bytes).use { image -> image.toComposeImageBitmap() }
}
