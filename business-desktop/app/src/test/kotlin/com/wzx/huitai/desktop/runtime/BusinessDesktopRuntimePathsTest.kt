package com.wzx.huitai.desktop.runtime

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.UUID
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessDesktopRuntimePathsTest {
    @Test
    fun `creates the exact isolated agent and desktop tree`() {
        val home = Files.createTempDirectory("huitai-runtime-home")

        val paths = BusinessDesktopRuntimePaths.create(home)

        val root = home.resolve(".huitai-agent-desktop")
        assertEquals(root.resolve("agent"), paths.agentRoot)
        assertEquals(root.resolve("agent/data/babiq-business.db"), paths.agentDatabase)
        assertEquals(root.resolve("agent/secrets/business-agent.jceks"), paths.agentKeyStore)
        assertEquals(root.resolve("agent/logs/backend.log"), paths.agentLog)
        assertEquals(root.resolve("agent/memory"), paths.agentMemoryRoot)
        assertEquals(root.resolve("agent/teams"), paths.agentTeamRoot)
        assertEquals(root.resolve("agent/instance.lock"), paths.agentInstanceLock)
        assertEquals(root.resolve("agent/session-token"), paths.agentSessionToken)
        assertEquals(root.resolve("desktop"), paths.desktopRoot)
        assertEquals(root.resolve("desktop/data/business-desktop.db"), paths.desktopDatabase)
        assertEquals(root.resolve("desktop/secrets/business-desktop.jceks"), paths.desktopKeyStore)
        assertEquals(root.resolve("desktop/logs/desktop.log"), paths.desktopLog)
        assertEquals(root.resolve("desktop/instance.lock"), paths.desktopInstanceLock)
        assertEquals(root.resolve("desktop/installation-id"), paths.desktopInstallationId)

        assertTrue(paths.agentMemoryRoot.exists())
        assertTrue(paths.agentTeamRoot.exists())
        assertTrue(paths.desktopDatabase.parent.exists())
        assertFalse(paths.agentSessionToken.exists(), "runtime setup must never pre-create the one-shot token")
        assertFalse(paths.desktopInstallationId.exists(), "runtime setup must not invent installation identity")
    }

    @Test
    fun `stable installation id is an atomic UUID value`() {
        val paths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-installation"))
        val store = DesktopInstallationIdentityStore(paths.desktopInstallationId)

        val first = store.loadOrCreate()
        val second = store.loadOrCreate()

        assertEquals(first, second)
        assertEquals(first, UUID.fromString(first).toString())
        assertEquals(first, Files.readString(paths.desktopInstallationId))
        assertFalse(paths.desktopInstallationId.resolveSibling("installation-id.tmp").exists())
        assertFalse(store.toString().contains(first))
    }

    @Test
    fun `rejects symbolic link traversal below controlled runtime root`() {
        val home = Files.createTempDirectory("huitai-linked-home")
        val outside = Files.createTempDirectory("huitai-outside")
        val root = home.resolve(".huitai-agent-desktop")
        Files.createDirectories(root)
        val linkedAgent = root.resolve("agent")
        val linkCreated = runCatching { Files.createSymbolicLink(linkedAgent, outside) }.isSuccess
        if (!linkCreated) return

        assertFailsWith<IllegalArgumentException> {
            BusinessDesktopRuntimePaths.create(home)
        }
    }

    @Test
    fun `rejects an existing controlled leaf symbolic link without touching its target`() {
        val home = Files.createTempDirectory("huitai-linked-leaf-home")
        val outside = Files.createTempFile("huitai-outside-installation", ".txt")
        Files.writeString(outside, "outside-value")
        val desktop = home.resolve(".huitai-agent-desktop/desktop")
        Files.createDirectories(desktop)
        val leaf = desktop.resolve("installation-id")
        val linkCreated = runCatching { Files.createSymbolicLink(leaf, outside) }.isSuccess
        if (!linkCreated) return

        assertFailsWith<IllegalArgumentException> {
            BusinessDesktopRuntimePaths.create(home)
        }
        assertEquals("outside-value", Files.readString(outside))
    }

    @Test
    fun `concurrent same JVM installation identity creation returns one stable value`() {
        val paths = BusinessDesktopRuntimePaths.create(Files.createTempDirectory("huitai-installation-race"))
        val executor = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        try {
            val futures = (1..48).map {
                executor.submit<String> {
                    start.await()
                    DesktopInstallationIdentityStore(paths.desktopInstallationId).loadOrCreate()
                }
            }
            start.countDown()
            val values = futures.map { it.get() }

            assertEquals(1, values.toSet().size)
            assertEquals(values.first(), Files.readString(paths.desktopInstallationId))
        } finally {
            executor.shutdownNow()
        }
    }
}
