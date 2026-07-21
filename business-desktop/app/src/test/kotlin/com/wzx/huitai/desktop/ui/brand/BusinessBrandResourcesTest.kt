package com.wzx.huitai.desktop.ui.brand

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

        assertNotNull(mascot)
        assertTrue(mascot.width >= 512 && mascot.height >= 512)
        assertTrue(mascot.colorModel.hasAlpha())
        assertEquals(mascot.width, mascotBitmap.width)
        assertEquals(mascot.height, mascotBitmap.height)
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
            }
        }
        assertTrue(sizes.size >= 4)
        assertTrue(256 to 256 in sizes)
    }

    @Test
    fun `resource loader does not depend on development machine paths`() {
        val source = Path.of(
            "src", "main", "kotlin", "com", "wzx", "huitai", "desktop", "ui", "brand",
            "BusinessBrandResources.kt",
        ).toFile().readText()

        assertTrue("C:\\Users" !in source)
        assertTrue("E:\\huitai-work" !in source)
    }
}
