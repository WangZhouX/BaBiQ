package com.wzx.huitai.desktop.integration

import com.wzx.huitai.agent.business.auth.BusinessAuthStatus
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.agent.conversation.BusinessAgentClient
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.JsonRpcSuccessResponse
import com.wzx.huitai.agent.business.auth.BusinessAuthRpcClient
import com.wzx.huitai.desktop.auth.BusinessAccessGateState
import com.wzx.huitai.desktop.auth.BusinessAuthenticationLifecycle
import com.wzx.huitai.desktop.auth.BusinessIdentityRegistry
import com.wzx.huitai.desktop.auth.BusinessLoginErrorCode
import com.wzx.huitai.desktop.auth.ReadyAgentUsageGate
import com.wzx.huitai.desktop.auth.BusinessRpcAuthenticationOperations
import com.wzx.huitai.desktop.controller.BusinessConversationController
import com.wzx.huitai.desktop.state.BusinessDesktopReducer
import com.wzx.huitai.desktop.state.BusinessDesktopStore
import com.wzx.huitai.desktop.state.BusinessIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Verifies the reconnect contract remains session probe -> opaque attach, without OA credentials. */
@OptIn(ExperimentalCoroutinesApi::class)
class BusinessAuthenticationReconnectIT {
    @Test
    fun `real authentication lifecycle survives cleanup failure then attaches after reconnect`() = runTest {
        val connection = ReconnectBackendConnection()
        val rpc = AgentJsonRpcClient(connection, this)
        val registry = BusinessIdentityRegistry()
        val recoveryStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseRecovery = kotlinx.coroutines.CompletableDeferred<Unit>()
        var recoveryCount = 0
        val authentication = BusinessRpcAuthenticationOperations(
            client = BusinessAuthRpcClient(rpc),
            identityRegistry = registry,
            desktopInstanceId = "desktop-reconnect-it",
            desktopSessionId = "session-reconnect-it",
            platformId = 2,
            onRecovering = {
                recoveryCount += 1
                recoveryStarted.complete(Unit)
                releaseRecovery.await()
                throw IllegalStateException("local recovery cleanup failed")
            },
        )
        val supervisor = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connected("connection-1"))
        val lifecycle = BusinessAuthenticationLifecycle(authentication, supervisor, this)

        try {
            lifecycle.start()
            runCurrent()
            assertEquals(listOf("business/auth/session/get"), connection.methods)
            assertEquals(BusinessAccessGateState.READY, registry.gate.value)
            assertEquals(8, registry.currentIdentity()?.identityEpoch)

            supervisor.value = AgentSupervisorState.Reconnecting(consecutiveFailures = 1, delayMillis = 0)
            recoveryStarted.await()
            assertEquals(BusinessAccessGateState.RESTORING, registry.gate.value)

            supervisor.value = AgentSupervisorState.Connected("connection-2")
            runCurrent()
            assertEquals(listOf("business/auth/session/get"), connection.methods)

            releaseRecovery.complete(Unit)
            withTimeout(2_000) { registry.gate.first { it == BusinessAccessGateState.READY } }

            assertEquals(9, registry.currentIdentity()?.identityEpoch)
            assertEquals("auth-session-9", registry.currentIdentity()?.authSessionId)
            assertEquals(1, recoveryCount)
            assertEquals(
                listOf(
                    "business/auth/session/get",
                    "business/auth/session/get",
                    "business/auth/session/attach",
                ),
                connection.methods,
            )
            assertEquals(setOf("attachHandle"), connection.attachParams.single().keys)
            assertTrue(connection.attachParams.single().toString().contains("attach-handle-9"))
            assertTrue(connection.requests.none { it.toString().contains("accessToken") })
            assertTrue(connection.requests.none { it.toString().contains("refreshToken") })
        } finally {
            lifecycle.shutdown()
            rpc.close()
        }
        assertTrue("business/auth/logout" !in connection.methods)
    }

    @Test
    fun `raw terminal auth notification reconciles while reconnect gate is restoring without identity epoch`() = runTest {
        val connection = TerminalNotificationBackendConnection()
        val rpc = AgentJsonRpcClient(connection, this)
        val registry = BusinessIdentityRegistry().apply {
            check(publishReady(reconnectingIdentity(), expectedGeneration = currentGeneration()))
        }
        val signedOut = CompletableDeferred<Unit>()
        val authentication = BusinessRpcAuthenticationOperations(
            client = BusinessAuthRpcClient(rpc),
            identityRegistry = registry,
            desktopInstanceId = "desktop-reconnect-it",
            desktopSessionId = "session-reconnect-it",
            platformId = 2,
            onSignedOut = { signedOut.complete(Unit) },
        )
        authentication.onConnectionUnavailable()
        assertEquals(BusinessAccessGateState.RESTORING, registry.gate.value)

        val client = BusinessAgentClient(rpc, this)
        val controller = BusinessConversationController(
            gateway = client,
            store = BusinessDesktopStore(BusinessDesktopReducer()),
            usageGate = ReadyAgentUsageGate(registry),
            scope = this,
            onAuthStateChanged = authentication::reconcileAuthStateChanged,
        )

        try {
            connection.serverNotifyAuthenticationExpired()
            withTimeout(2_000) { signedOut.await() }

            assertEquals(BusinessAccessGateState.SIGNED_OUT, registry.gate.value)
            assertNull(registry.currentIdentity())
            assertEquals(BusinessLoginErrorCode.AUTH_EXPIRED, authentication.lastError.value?.code)
            assertEquals(listOf("business/auth/session/get"), connection.methods)
            assertTrue("business/auth/logout" !in connection.methods)
            assertTrue("identityEpoch" !in connection.notificationParams.single())
        } finally {
            controller.close()
            rpc.close()
        }
    }

    private fun reconnectingIdentity() = BusinessIdentity(
        desktopInstanceId = "desktop-reconnect-it",
        desktopSessionId = "session-reconnect-it",
        authSessionId = "auth-session-8",
        identityEpoch = 8,
        userId = "user-8",
        tenantId = "tenant-8",
        platformId = "2",
        roles = setOf("LAWYER"),
        permissions = setOf("workbench:read"),
    )

    private class ReconnectBackendConnection : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        val requests = mutableListOf<JsonObject>()
        val methods = mutableListOf<String>()
        val attachParams = mutableListOf<JsonObject>()
        private var sessionCallCount = 0

        override val connectionId: String = "reconnect-it-connection"
        override val incoming: ReceiveChannel<String> = incomingChannel
        override val state = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true

        override suspend fun send(text: String) {
            val request = ApplicationProtocol.JSON.parseToJsonElement(text).jsonObject
            requests += request
            val id = request.getValue("id").jsonPrimitive.long
            val method = request.getValue("method").jsonPrimitive.content
            methods += method
            val params = request.getValue("params").jsonObject
            if (method == "business/auth/session/attach") attachParams += params
            val result = when (method) {
                "business/auth/session/get" -> {
                    sessionCallCount += 1
                    if (sessionCallCount == 1) {
                        buildJsonObject {
                            put("status", "READY")
                            put("authSessionId", "auth-session-8")
                            put("identityEpoch", 8)
                            put("generation", 16)
                            put("platformId", "2")
                            put("user", buildJsonObject { put("id", "user-8"); put("name", "Lawyer") })
                            put("tenant", buildJsonObject { put("id", "tenant-8"); put("name", "Tenant") })
                        }
                    } else {
                        buildJsonObject {
                            put("status", "DETACHED")
                            put("identityEpoch", 8)
                            put("generation", 17)
                            put("attachHandle", "attach-handle-9")
                        }
                    }
                }
                "business/auth/session/attach" -> buildJsonObject {
                    put("status", "READY")
                    put("authSessionId", "auth-session-9")
                    put("identityEpoch", 9)
                    put("generation", 18)
                    put("platformId", "2")
                    put("user", buildJsonObject { put("id", "user-9"); put("name", "Lawyer") })
                    put("tenant", buildJsonObject { put("id", "tenant-9"); put("name", "Tenant") })
                }
                else -> error("Unexpected method: $method")
            }
            incomingChannel.send(
                ApplicationProtocol.JSON.encodeToString(
                    JsonRpcSuccessResponse.serializer(),
                    JsonRpcSuccessResponse(id = id, result = result),
                ),
            )
        }

        override suspend fun close() {
            incomingChannel.close()
        }
    }

    private class TerminalNotificationBackendConnection : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        val methods = mutableListOf<String>()
        val notificationParams = mutableListOf<JsonObject>()

        override val connectionId: String = "terminal-notification-connection"
        override val incoming: ReceiveChannel<String> = incomingChannel
        override val state = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true

        override suspend fun send(text: String) {
            val request = ApplicationProtocol.JSON.parseToJsonElement(text).jsonObject
            val id = request.getValue("id").jsonPrimitive.long
            val method = request.getValue("method").jsonPrimitive.content
            methods += method
            check(method == "business/auth/session/get") { "Unexpected method: $method" }
            val result = buildJsonObject {
                put("status", "SIGNED_OUT")
                put("authSessionId", "auth-session-8")
                put("identityEpoch", 0)
                put("generation", 17)
            }
            incomingChannel.send(
                ApplicationProtocol.JSON.encodeToString(
                    JsonRpcSuccessResponse.serializer(),
                    JsonRpcSuccessResponse(id = id, result = result),
                ),
            )
        }

        suspend fun serverNotifyAuthenticationExpired() {
            val params = buildJsonObject {
                put("authSessionId", "auth-session-8")
                put("state", "SIGNED_OUT")
                put("generation", 17)
                put("businessCode", "BUSINESS_AUTH_EXPIRED")
            }
            notificationParams += params
            incomingChannel.send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "business/auth/state-changed")
                put("params", params)
            }.toString())
        }

        override suspend fun close() {
            incomingChannel.close()
        }
    }

}
