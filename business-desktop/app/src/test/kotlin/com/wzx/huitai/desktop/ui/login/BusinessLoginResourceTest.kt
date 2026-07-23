package com.wzx.huitai.desktop.ui.login

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BusinessLoginResourceTest {
    @Test
    fun `original OA login artwork and animated logo are packaged and decodable`() {
        val backgroundBytes = resourceBytes("/brand/login_bg.png")
        val logoBytes = resourceBytes("/brand/xiangniao-law-logo.gif")

        val background = ImageIO.read(ByteArrayInputStream(backgroundBytes))
        val logo = ImageIO.read(ByteArrayInputStream(logoBytes))

        assertNotNull(background)
        assertTrue(background.width > background.height)
        assertTrue(background.width >= 1_000)
        assertNotNull(logo)
        assertTrue(logo.width > 0 && logo.height > 0)
        assertEquals(
            "7f2f2f4d6b0a1d4ad32067f5984c48f28de7cb642854d0c7a52fd01c483763d1",
            backgroundBytes.sha256(),
        )
        assertEquals(
            "b24ebfe13d3ed7e310d1bac4258b93424b2ecd8a7a9936b8c97f54e510f17cfe",
            logoBytes.sha256(),
        )
    }

    private fun resourceBytes(path: String): ByteArray =
        requireNotNull(BusinessLoginResourceTest::class.java.getResourceAsStream(path)) {
            "Missing packaged login resource: $path"
        }.use { it.readBytes() }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { "%02x".format(it) }
}
