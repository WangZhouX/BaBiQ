package com.wzx.huitai.agent.client

import com.wzx.huitai.agent.protocol.ApplicationProtocolLimits
import com.wzx.huitai.agent.protocol.ApplicationProtocolValidationException
import com.wzx.huitai.agent.protocol.ApplicationProtocolValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

class ApplicationSequenceTrackerTest {
    @Test
    fun `envelope sequence is positive and strictly increasing for one desktop session`() {
        val tracker = ApplicationSequenceTracker("desktop-session-1")

        tracker.acceptEnvelopeSequence("desktop-session-1", 1)
        tracker.acceptEnvelopeSequence("desktop-session-1", 2)

        listOf(0L, -1L, 1L, 2L).forEach { invalid ->
            assertFailsWith<ApplicationSequenceException> {
                tracker.acceptEnvelopeSequence("desktop-session-1", invalid)
            }
        }
        tracker.acceptEnvelopeSequence("desktop-session-1", 3)
    }

    @Test
    fun `new connection accepts first positive republish then requires strict local increase`() {
        val tracker = ApplicationSequenceTracker("desktop-session-1")

        tracker.acceptIdentityEpoch("connection-1", 8)
        tracker.acceptCatalogEpoch("connection-1", 13)
        tracker.acceptContextSequence("connection-1", 21)
        tracker.acceptIdentityEpoch("connection-1", 9)
        tracker.acceptCatalogEpoch("connection-1", 14)
        tracker.acceptContextSequence("connection-1", 22)

        tracker.acceptIdentityEpoch("connection-2", 8)
        tracker.acceptCatalogEpoch("connection-2", 13)
        tracker.acceptContextSequence("connection-2", 21)

        listOf(0L, 8L).forEach { invalid ->
            assertFailsWith<ApplicationSequenceException> {
                tracker.acceptIdentityEpoch("connection-2", invalid)
            }
        }
        listOf(0L, 13L).forEach { invalid ->
            assertFailsWith<ApplicationSequenceException> {
                tracker.acceptCatalogEpoch("connection-2", invalid)
            }
        }
        listOf(0L, 21L).forEach { invalid ->
            assertFailsWith<ApplicationSequenceException> {
                tracker.acceptContextSequence("connection-2", invalid)
            }
        }
    }

    @Test
    fun `rejected stale local values do not move the accepted high water mark`() {
        val tracker = ApplicationSequenceTracker("desktop-session-1")

        tracker.acceptIdentityEpoch("connection-1", 8)
        tracker.acceptCatalogEpoch("connection-1", 13)
        tracker.acceptContextSequence("connection-1", 21)
        assertFailsWith<ApplicationSequenceException> {
            tracker.acceptIdentityEpoch("connection-1", 7)
        }
        assertFailsWith<ApplicationSequenceException> {
            tracker.acceptCatalogEpoch("connection-1", 12)
        }
        assertFailsWith<ApplicationSequenceException> {
            tracker.acceptContextSequence("connection-1", 20)
        }

        listOf(
            { tracker.acceptIdentityEpoch("connection-1", 8) },
            { tracker.acceptCatalogEpoch("connection-1", 13) },
            { tracker.acceptContextSequence("connection-1", 21) },
        ).forEach { stillEqualToHighWaterMark ->
            assertFailsWith<ApplicationSequenceException> { stillEqualToHighWaterMark() }
        }
    }

    @Test
    fun `new connection resets local counters but never resets session envelope sequence`() {
        val tracker = ApplicationSequenceTracker("desktop-session-1")
        tracker.acceptEnvelopeSequence("desktop-session-1", 5)
        tracker.acceptIdentityEpoch("connection-1", 30)
        tracker.acceptCatalogEpoch("connection-1", 40)
        tracker.acceptContextSequence("connection-1", 50)

        tracker.acceptIdentityEpoch("connection-2", 1)
        tracker.acceptCatalogEpoch("connection-2", 1)
        tracker.acceptContextSequence("connection-2", 1)
        assertFailsWith<ApplicationSequenceException> {
            tracker.acceptEnvelopeSequence("desktop-session-1", 1)
        }
    }

    @Test
    fun `only a different desktop session resets envelope and local counters`() {
        val tracker = ApplicationSequenceTracker("desktop-session-1")
        tracker.acceptEnvelopeSequence("desktop-session-1", 5)
        tracker.acceptIdentityEpoch("connection-1", 30)
        tracker.acceptCatalogEpoch("connection-1", 40)
        tracker.acceptContextSequence("connection-1", 50)

        tracker.beginDesktopSession("desktop-session-1")
        assertFailsWith<ApplicationSequenceException> {
            tracker.acceptEnvelopeSequence("desktop-session-1", 1)
        }
        assertFailsWith<ApplicationSequenceException> {
            tracker.acceptIdentityEpoch("connection-1", 1)
        }

        tracker.beginDesktopSession("desktop-session-2")
        tracker.acceptEnvelopeSequence("desktop-session-2", 1)
        tracker.acceptIdentityEpoch("connection-1", 1)
        tracker.acceptCatalogEpoch("connection-1", 1)
        tracker.acceptContextSequence("connection-1", 1)
        assertFailsWith<ApplicationSequenceException> {
            tracker.acceptEnvelopeSequence("desktop-session-1", 2)
        }
    }

    @Test
    fun `all oversized protocol payload categories fail before transport send`() = runBlocking {
        val connection = RecordingConnection()
        val oversizedCases = listOf<suspend () -> Unit>(
            {
                ApplicationProtocolValidator.validateEnvelopeSize(
                    ByteArray(ApplicationProtocolLimits.MAX_ENVELOPE_BYTES + 1),
                )
                connection.send("envelope")
            },
            {
                ApplicationProtocolValidator.validateCatalogPayloadSize(
                    ByteArray(ApplicationProtocolLimits.MAX_CATALOG_PAYLOAD_BYTES + 1),
                )
                connection.send("catalog")
            },
            {
                ApplicationProtocolValidator.validateContextPayloadSize(
                    ByteArray(ApplicationProtocolLimits.MAX_CONTEXT_PAYLOAD_BYTES + 1),
                )
                connection.send("context")
            },
            {
                ApplicationProtocolValidator.validateActionInputSize(
                    ByteArray(ApplicationProtocolLimits.MAX_ACTION_INPUT_BYTES + 1),
                )
                connection.send("action-input")
            },
            {
                ApplicationProtocolValidator.validateActionResultSize(
                    ByteArray(ApplicationProtocolLimits.MAX_ACTION_RESULT_BYTES + 1),
                )
                connection.send("action-result")
            },
        )

        oversizedCases.forEach { validateThenSend ->
            assertFailsWith<ApplicationProtocolValidationException> { validateThenSend() }
        }
        assertEquals(0, connection.sendCount)
    }

    private class RecordingConnection : AgentConnection {
        override val connectionId: String = "connection-recording"
        override val incoming: ReceiveChannel<String> = Channel()
        override val state: StateFlow<AgentConnectionState> = MutableStateFlow(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true
        var sendCount: Int = 0
            private set

        override suspend fun send(text: String) {
            sendCount += 1
        }

        override suspend fun close() = Unit
    }
}
