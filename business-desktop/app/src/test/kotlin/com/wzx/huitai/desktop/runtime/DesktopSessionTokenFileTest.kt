package com.wzx.huitai.desktop.runtime

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopSessionTokenFileTest {
    @Test
    fun `each child launch gets a new session and 256 bit token`() {
        val paths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-token"))

        val first = DesktopSessionTokenFile.create(paths.agentSessionToken, "installation-id")
        val firstToken = first.identity.desktopSessionToken
        assertEquals(32, Base64.getUrlDecoder().decode(firstToken).size)
        assertEquals(firstToken, Files.readString(paths.agentSessionToken))
        first.close()

        val second = DesktopSessionTokenFile.create(paths.agentSessionToken, "installation-id")
        assertNotEquals(first.identity.desktopSessionId, second.identity.desktopSessionId)
        assertNotEquals(firstToken, second.identity.desktopSessionToken)
        assertFalse(second.toString().contains(second.identity.desktopSessionToken))
        assertFalse(second.toString().contains(second.identity.desktopSessionId))
        second.close()
        assertFalse(paths.agentSessionToken.exists())
    }

    @Test
    fun `create new prevents overwriting an unconsumed token and close cleans it`() {
        val paths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-token-existing"))
        val first = DesktopSessionTokenFile.create(paths.agentSessionToken, "installation-id")

        assertFailsWith<IllegalStateException> {
            DesktopSessionTokenFile.create(paths.agentSessionToken, "installation-id")
        }
        assertEquals(first.identity.desktopSessionToken, Files.readString(paths.agentSessionToken))

        first.close()
        assertFalse(paths.agentSessionToken.exists())
        first.close()
    }

    @Test
    fun `refuses a token symlink without touching its target`() {
        val paths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-token-link"))
        val target = Files.createTempFile("huitai-token-target", ".txt")
        Files.writeString(target, "keep", StandardOpenOption.TRUNCATE_EXISTING)
        val linkCreated = runCatching { Files.createSymbolicLink(paths.agentSessionToken, target) }.isSuccess
        if (!linkCreated) return

        assertFailsWith<IllegalArgumentException> {
            DesktopSessionTokenFile.create(paths.agentSessionToken, "installation-id")
        }
        assertEquals("keep", Files.readString(target))
        assertTrue(paths.agentSessionToken.exists())
    }
}
