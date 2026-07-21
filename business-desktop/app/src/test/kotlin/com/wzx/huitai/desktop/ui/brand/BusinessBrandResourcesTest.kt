package com.wzx.huitai.desktop.ui.brand

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BusinessBrandResourcesTest {
    @Test
    fun `logo and mascot decode from the application classpath`() {
        val logo = ImageIO.read(ByteArrayInputStream(BusinessBrandResources.logoBytes()))
        val mascot = ImageIO.read(ByteArrayInputStream(BusinessBrandResources.mascotBytes()))
        val logoBitmap = BusinessBrandResources.logoImageBitmap()
        val mascotBitmap = BusinessBrandResources.mascotImageBitmap()

        assertNotNull(logo)
        assertTrue(logo.width >= 64 && logo.height >= 64)
        assertEquals(logo.width, logo.height)
        assertEquals(logo.width, logoBitmap.width)
        assertEquals(logo.height, logoBitmap.height)
        assertSame(logoBitmap, BusinessBrandResources.logoImageBitmap())

        assertNotNull(mascot)
        assertTrue(mascot.width >= 512 && mascot.height >= 512)
        assertTrue(mascot.colorModel.hasAlpha())
        assertEquals(mascot.width, mascotBitmap.width)
        assertEquals(mascot.height, mascotBitmap.height)
        assertSame(mascotBitmap, BusinessBrandResources.mascotImageBitmap())
        assertTrue(
            (0 until mascot.height).any { y ->
                (0 until mascot.width).any { x -> (mascot.getRGB(x, y) ushr 24) == 0 }
            },
            "mascot must contain transparent background pixels",
        )
    }

    @Test
    fun `windows icon contains multiple embedded sizes`() {
        val bytes = BusinessBrandResources.windowsIconBytes()
        val directory = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0, directory.short.toInt())
        assertEquals(1, directory.short.toInt())
        val count = directory.short.toInt()
        assertTrue(count >= 4, "Windows ICO should contain at least four image sizes")

        val sizes = buildSet {
            repeat(count) {
                val widthByte = directory.get().toInt() and 0xff
                val heightByte = directory.get().toInt() and 0xff
                val width = if (widthByte == 0) 256 else widthByte
                val height = if (heightByte == 0) 256 else heightByte
                add(width to height)
                directory.position(directory.position() + 6)
                val payloadSize = directory.int
                val payloadOffset = directory.int
                assertTrue(payloadSize > 0)
                assertTrue(payloadOffset >= 6 + count * 16)
                assertTrue(payloadOffset + payloadSize <= bytes.size)
                assertEquals(
                    width to height,
                    embeddedImageSize(bytes, payloadOffset, payloadSize),
                    "ICO directory size must match its embedded PNG/DIB payload",
                )
            }
        }
        assertTrue(sizes.size >= 4)
        assertTrue(256 to 256 in sizes)
    }

    @Test
    fun `decoder lets errors escape unchanged`() {
        val fatal = AssertionError("fatal decoder failure")

        val thrown = assertFailsWith<AssertionError> {
            BusinessBrandResources.decodeImage(ByteArray(0), "/brand/test.png") {
                throw fatal
            }
        }

        assertSame(fatal, thrown)
    }

    @Test
    fun `resource paths are portable classpath root paths`() {
        val paths = listOf(
            BusinessBrandResources.LOGO_PATH,
            BusinessBrandResources.MASCOT_PATH,
            BusinessBrandResources.WINDOWS_ICON_PATH,
        )

        paths.forEach { path ->
            assertTrue(path.startsWith("/brand/"))
            assertTrue('\\' !in path)
            assertTrue(':' !in path, "classpath path must not contain a drive or URI scheme")
            assertNotNull(BusinessBrandResources::class.java.getResource(path))
        }
    }

    private fun embeddedImageSize(bytes: ByteArray, offset: Int, size: Int): Pair<Int, Int> {
        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val payload = ByteBuffer.wrap(bytes, offset, size).slice()
        if (size >= 24 && bytes.copyOfRange(offset, offset + pngSignature.size).contentEquals(pngSignature)) {
            payload.order(ByteOrder.BIG_ENDIAN)
            return payload.getInt(16) to payload.getInt(20)
        }

        payload.order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = if (size >= 12) payload.getInt(0) else -1
        assertTrue(headerSize in setOf(40, 108, 124), "ICO payload must be PNG or a supported DIB")
        val width = payload.getInt(4)
        val combinedHeight = payload.getInt(8)
        assertTrue(width > 0 && combinedHeight > 0 && combinedHeight % 2 == 0)
        return width to combinedHeight / 2
    }
}
