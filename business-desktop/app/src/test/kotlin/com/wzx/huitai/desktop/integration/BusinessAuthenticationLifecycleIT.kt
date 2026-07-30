package com.wzx.huitai.desktop.integration

import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.desktop.auth.BusinessAuthenticationLifecycle
import com.wzx.huitai.desktop.auth.BusinessAuthenticationLifecycleOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessAuthenticationLifecycleIT {
    @Test
    fun `first connected performs startup restore and only a later new connection id attaches`() = runTest {
        val supervisor = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connecting)
        val operations = RecordingLifecycleOperations()
        val lifecycle = BusinessAuthenticationLifecycle(operations, supervisor, this)

        lifecycle.start()
        runCurrent()
        assertEquals(0, operations.restoreCount)

        supervisor.value = AgentSupervisorState.Connected("connection-1")
        runCurrent()
        assertEquals(1, operations.restoreCount)
        assertEquals(0, operations.attachCount)

        supervisor.value = AgentSupervisorState.Reconnecting(consecutiveFailures = 1, delayMillis = 0)
        runCurrent()
        assertEquals(1, operations.connectionUnavailableCount)

        supervisor.value = AgentSupervisorState.Connected("connection-1")
        runCurrent()
        assertEquals(1, operations.restoreCount)
        assertEquals(0, operations.attachCount)

        supervisor.value = AgentSupervisorState.Connected("connection-2")
        runCurrent()
        assertEquals(1, operations.restoreCount)
        assertEquals(1, operations.attachCount)
        assertEquals(1, operations.connectionUnavailableCount)

        lifecycle.shutdown()
        assertEquals(1, operations.closeCount)
    }

    @Test
    fun `reconnecting invalidates local authentication before a replacement connection arrives`() = runTest {
        val supervisor = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connected("connection-1"))
        val operations = RecordingLifecycleOperations()
        val lifecycle = BusinessAuthenticationLifecycle(operations, supervisor, this)
        lifecycle.start()
        runCurrent()
        assertEquals(listOf("restore"), operations.events)

        supervisor.value = AgentSupervisorState.Reconnecting(consecutiveFailures = 1, delayMillis = 5_000)
        runCurrent()

        assertEquals(listOf("restore", "connection-unavailable"), operations.events)
        lifecycle.shutdown()
    }

    @Test
    fun `new connection cancels an older reconnect attach before starting the next attach`() = runTest {
        val supervisor = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connected("connection-1"))
        val operations = RecordingLifecycleOperations(blockFirstAttach = true)
        val lifecycle = BusinessAuthenticationLifecycle(operations, supervisor, this)
        lifecycle.start()
        runCurrent()

        supervisor.value = AgentSupervisorState.Connected("connection-2")
        operations.firstAttachStarted.await()

        supervisor.value = AgentSupervisorState.Connected("connection-3")
        operations.firstAttachCancelled.await()
        runCurrent()

        assertEquals(2, operations.attachCount)
        assertEquals(
            listOf(
                "restore",
                "connection-unavailable",
                "attach-1",
                "connection-unavailable",
                "attach-1-cancelled",
                "attach-2",
            ),
            operations.events,
        )
        lifecycle.shutdown()
    }

    @Test
    fun `connection cleanup cancellation stops observing replacement connections`() = runTest {
        val supervisor = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connected("connection-1"))
        val operations = RecordingLifecycleOperations(
            connectionUnavailableFailure = CancellationException("cleanup cancelled"),
        )
        val lifecycle = BusinessAuthenticationLifecycle(operations, supervisor, this)
        lifecycle.start()
        runCurrent()

        supervisor.value = AgentSupervisorState.Reconnecting(consecutiveFailures = 1, delayMillis = 0)
        runCurrent()
        supervisor.value = AgentSupervisorState.Connected("connection-2")
        runCurrent()

        assertEquals(0, operations.attachCount)
        assertEquals(listOf("restore", "connection-unavailable"), operations.events)
        lifecycle.shutdown()
    }

    private class RecordingLifecycleOperations(
        private val blockFirstAttach: Boolean = false,
        private val connectionUnavailableFailure: Throwable? = null,
    ) : BusinessAuthenticationLifecycleOperations {
        var restoreCount = 0
        var attachCount = 0
        var connectionUnavailableCount = 0
        var closeCount = 0
        val events = mutableListOf<String>()
        val firstAttachStarted = CompletableDeferred<Unit>()
        val firstAttachCancelled = CompletableDeferred<Unit>()

        override suspend fun restore() {
            restoreCount += 1
            events += "restore"
        }

        override suspend fun attachAfterReconnect() {
            attachCount += 1
            val ordinal = attachCount
            events += "attach-$ordinal"
            if (blockFirstAttach && ordinal == 1) {
                firstAttachStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    events += "attach-1-cancelled"
                    firstAttachCancelled.complete(Unit)
                }
            }
        }

        override suspend fun onConnectionUnavailable() {
            connectionUnavailableCount += 1
            events += "connection-unavailable"
            connectionUnavailableFailure?.let { throw it }
        }

        override suspend fun close() {
            closeCount += 1
        }
    }
}
