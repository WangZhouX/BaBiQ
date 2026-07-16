package com.wzx.huitai.security.instance

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessInstanceLockTest {
    @Test
    fun `same path collides until first lock closes`() {
        val path = Files.createTempDirectory("process-instance-lock").resolve("desktop.lock")
        val first = ProcessInstanceLock.acquire(path)

        val collision = assertFailsWith<ProcessInstanceLockException> {
            ProcessInstanceLock.acquire(path)
        }
        assertEquals("Process instance lock is already held", collision.message)
        assertFalse(path.toAbsolutePath().toString() in collision.toString())
        assertFalse(path.toAbsolutePath().toString() in first.toString())

        first.close()
        first.close()
        ProcessInstanceLock.acquire(path).use { reacquired ->
            assertEquals("ProcessInstanceLock(path=[REDACTED])", reacquired.toString())
        }
    }

    @Test
    fun `different desktop and agent paths can be held together and create parents`() {
        val root = Files.createTempDirectory("process-instance-distinct")
        val desktopPath = root.resolve("nested/desktop.lock")
        val agentPath = root.resolve("nested/agent.lock")

        ProcessInstanceLock.acquire(desktopPath).use {
            ProcessInstanceLock.acquire(agentPath).use {
                assertTrue(Files.exists(desktopPath.parent))
                assertTrue(Files.exists(desktopPath))
                assertTrue(Files.exists(agentPath))
            }
        }
    }

    @Test
    fun `concurrent acquisition has exactly one winner and releases afterward`() = runBlocking {
        val path = Files.createTempDirectory("process-instance-concurrent").resolve("desktop.lock")
        val dispatcher = Executors.newFixedThreadPool(32).asCoroutineDispatcher()
        val start = CountDownLatch(1)
        val attempted = CountDownLatch(32)
        try {
            val results = (1..32).map {
                async(dispatcher) {
                    start.await()
                    try {
                        val acquired = ProcessInstanceLock.acquire(path)
                        attempted.countDown()
                        attempted.await(10, TimeUnit.SECONDS)
                        acquired
                    } catch (collision: ProcessInstanceLockException) {
                        attempted.countDown()
                        collision
                    }
                }
            }
            start.countDown()
            val completed = results.awaitAll()
            val winners = completed.filterIsInstance<ProcessInstanceLock>()
            val collisions = completed.filterIsInstance<ProcessInstanceLockException>()
            assertEquals(1, winners.size)
            assertEquals(31, collisions.size)
            assertTrue(collisions.all { it.message == "Process instance lock is already held" })
            winners.single().close()
            ProcessInstanceLock.acquire(path).close()
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `failed locking closes opened channel`() {
        val path = Files.createTempDirectory("process-instance-cleanup").resolve("desktop.lock")
        lateinit var opened: FileChannel

        assertFailsWith<ProcessInstanceLockException> {
            ProcessInstanceLock.acquire(
                path = path,
                channelOpener = { normalized ->
                    FileChannel.open(normalized, StandardOpenOption.CREATE, StandardOpenOption.WRITE).also { opened = it }
                },
                tryLocker = { throw IllegalStateException("synthetic failure") },
            )
        }

        assertFalse(opened.isOpen)
    }

    @Test
    fun `close retries release and channel cleanup after both fail once`() {
        val path = Files.createTempDirectory("process-instance-close-retry").resolve("desktop.lock")
        var releaseAttempts = 0
        var closeAttempts = 0
        val instance = ProcessInstanceLock.acquire(
            path = path,
            lockReleaser = { lock ->
                releaseAttempts += 1
                if (releaseAttempts == 1) throw IllegalStateException("release failed")
                lock.release()
            },
            channelCloser = { channel ->
                closeAttempts += 1
                if (closeAttempts == 1) throw IllegalStateException("close failed")
                channel.close()
            },
        )

        val failure = assertFailsWith<ProcessInstanceLockException> { instance.close() }
        assertEquals("release failed", failure.cause?.message)
        assertEquals(listOf("close failed"), failure.cause?.suppressed?.map { it.message })
        instance.close()
        instance.close()
        assertEquals(2, releaseAttempts)
        assertEquals(2, closeAttempts)
        ProcessInstanceLock.acquire(path).close()
    }

    @Test
    fun `channel close completing after release failure makes later close idempotent`() {
        val path = Files.createTempDirectory("process-instance-close-channel").resolve("desktop.lock")
        var releaseAttempts = 0
        var closeAttempts = 0
        val instance = ProcessInstanceLock.acquire(
            path = path,
            lockReleaser = {
                releaseAttempts += 1
                throw IllegalStateException("release failed")
            },
            channelCloser = { channel ->
                closeAttempts += 1
                channel.close()
            },
        )

        assertFailsWith<ProcessInstanceLockException> { instance.close() }
        instance.close()
        assertEquals(1, releaseAttempts)
        assertEquals(1, closeAttempts)
        ProcessInstanceLock.acquire(path).close()
    }
}
