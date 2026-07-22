package com.wzx.huitai.desktop.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.agent.client.ApplicationSequenceTracker
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import com.wzx.huitai.desktop.app.BusinessAgentChildHandle
import com.wzx.huitai.desktop.app.BusinessAgentConnectionHandle
import com.wzx.huitai.desktop.app.BusinessAgentConnector
import com.wzx.huitai.desktop.app.BusinessAgentChildLauncher
import com.wzx.huitai.desktop.app.BusinessDesktopCompositionRoot
import com.wzx.huitai.desktop.app.BusinessDesktopProductionConfiguration
import com.wzx.huitai.desktop.app.CompositionResource
import com.wzx.huitai.desktop.app.DesktopSecretBootstrap
import com.wzx.huitai.desktop.app.ProductionBusinessDesktopCompositionFactory
import com.wzx.huitai.desktop.auth.BusinessAccessGateState
import com.wzx.huitai.desktop.logging.DesktopLoggingBootstrap
import com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths
import com.wzx.huitai.desktop.runtime.ManagedBusinessAgentConnection
import com.wzx.huitai.desktop.security.BusinessAuthSessionMetadata
import com.wzx.huitai.desktop.security.BusinessAuthSessionMetadataStore
import com.wzx.huitai.desktop.security.FileBusinessAuthRevocationMarkerStore
import com.wzx.huitai.desktop.security.JceksAuthCredentialPersistence
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.security.secret.JceksSecretStore
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BusinessAuthenticationLifecycleIT {
    @AfterTest
    fun resetLogging() {
        DesktopLoggingBootstrap.resetForTests()
    }

    @Test
    fun `valid durable token and metadata restore through registration transaction before ready`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val oaRequests = java.util.Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/law-api/system/auth/refresh-token") { exchange ->
            oaRequests += "${exchange.requestMethod} ${exchange.requestURI}"
            exchange.respond(
                """{"code":200,"msg":"ok","data":{"accessToken":"new-access","refreshToken":"new-refresh","userId":"user-1","expiresTime":4102444800000}}""",
            )
        }
        server.createContext("/law-api/system/auth/get-permission-info") { exchange ->
            oaRequests += "${exchange.requestMethod} ${exchange.requestURI}"
            exchange.respond(
                """{"code":200,"msg":"ok","data":{"permissions":["demo.write"],"roles":["lawyer"],"menus":[],"user":{"id":"user-1","name":"Lawyer"}}}""",
            )
        }
        server.start()
        val home = Files.createTempDirectory("business-auth-lifecycle-it")
        val password = "business-auth-it-password".toCharArray()
        val paths = BusinessDesktopRuntimePaths.create(home)
        Files.writeString(
            paths.desktopConfiguration,
            """
            huitai.oa.base-url=http://127.0.0.1:${server.address.port}
            huitai.oa.api-prefix=/law-api
            huitai.oa.platform-id=2
            huitai.oa.request-timeout-ms=5000
            huitai.oa.service-agreement-url=https://example.com/agreement
            huitai.oa.privacy-policy-url=https://example.com/privacy
            huitai.oa.allow-insecure-http=true
            """.trimIndent(),
        )
        JceksSecretStore(paths.desktopKeyStore, password.copyOf()).use { secrets ->
            JceksAuthCredentialPersistence(secrets).replace(AuthTokenSet("old-access", "old-refresh"))
            BusinessAuthSessionMetadataStore(secrets).saveOrReplace(
                BusinessAuthSessionMetadata("user-1", "tenant-1", "2"),
            )
        }
        FileBusinessAuthRevocationMarkerStore(paths.desktopAuthRevocationMarker).clearAfterExplicitLogin()
        FileBusinessAuthRevocationMarkerStore(paths.desktopAuthRevocationFallbackMarker).clearAfterExplicitLogin()
        val connection = TestAgentConnection()
        val productionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val factory = ProductionBusinessDesktopCompositionFactory(
            configuration = BusinessDesktopProductionConfiguration(
                home = home,
                backendJar = home.resolve("unused.jar"),
                desktopSecretBootstrap = DesktopSecretBootstrap { password.copyOf() },
            ),
            parentScope = productionScope,
            childLauncher = BusinessAgentChildLauncher { context ->
                val session = DesktopSessionIdentity.forChildLaunch(context.desktopInstanceId, "http://127.0.0.1")
                BusinessAgentChildHandle(
                    identity = session,
                    sequenceTracker = ApplicationSequenceTracker(session.desktopSessionId),
                    resource = CompositionResource { },
                )
            },
            connector = BusinessAgentConnector {
                BusinessAgentConnectionHandle(connection, CompositionResource { connection.close() })
            },
        )

        var root: BusinessDesktopCompositionRoot? = null
        try {
            val startedRoot = BusinessDesktopCompositionRoot.start(factory)
            root = startedRoot
            val runtimeView = assertNotNull(startedRoot.runtimeView)
            val production = runtimeView.production
            val ready = withContext(Dispatchers.Default) {
                withTimeoutOrNull(10_000) {
                    production.authenticationGate.first { it == BusinessAccessGateState.READY }
                }
            }
            assertEquals(
                BusinessAccessGateState.READY,
                ready,
                "gate=${production.authenticationGate.value}, error=${production.authenticationError.value?.code}, " +
                    "oaRequests=$oaRequests, agentMessages=${connection.snapshot()}",
            )

            val methods = connection.snapshot().mapNotNull { payload ->
                Json.parseToJsonElement(payload).jsonObject["method"]?.jsonPrimitive?.content
            }
            assertEquals(
                listOf(
                    "application/identity/update",
                    "application/identity/bind",
                    "application/catalog/register",
                    "application/context/publish",
                ),
                methods.filter { it.startsWith("application/") },
            )
            assertNotNull(runtimeView.desktopState.value.identity)

            startedRoot.shutdown()

            assertEquals(BusinessAccessGateState.SIGNED_OUT, production.authenticationGate.value)
            assertEquals(1, connection.closeCount)
            JceksSecretStore(paths.desktopKeyStore, password.copyOf()).use { secrets ->
                assertNull(JceksAuthCredentialPersistence(secrets).load())
                assertNull(BusinessAuthSessionMetadataStore(secrets).load())
            }
            assertTrue(FileBusinessAuthRevocationMarkerStore(paths.desktopAuthRevocationMarker).isRevoked())
            assertTrue(FileBusinessAuthRevocationMarkerStore(paths.desktopAuthRevocationFallbackMarker).isRevoked())
        } finally {
            root?.shutdown()
            server.stop(0)
            productionScope.cancel()
            password.fill('\u0000')
        }
    }

    private fun HttpExchange.respond(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private class TestAgentConnection : ManagedBusinessAgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        override val connectionId: String = "auth-it-connection"
        override val incoming: ReceiveChannel<String> = incomingChannel
        override val state: StateFlow<AgentConnectionState> = MutableStateFlow(AgentConnectionState.Connected)
        override val supervisorState: StateFlow<AgentSupervisorState> =
            MutableStateFlow(AgentSupervisorState.Connected(connectionId))
        override val hasConnected: Boolean = true
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        var closeCount = 0

        override suspend fun send(text: String) {
            sent += text
            val request = Json.parseToJsonElement(text).jsonObject
            val id = request["id"]?.jsonPrimitive?.content ?: return
            val result = if (request["method"]?.jsonPrimitive?.content == "provider/list") {
                """{"providers":[]}"""
            } else {
                "{}"
            }
            incomingChannel.send("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}")
        }

        override suspend fun close() {
            closeCount += 1
            incomingChannel.close()
        }

        override suspend fun manualRetry(): Boolean = false
        override suspend fun reconnect(expectedConnectionId: String): Boolean = false

        fun snapshot(): List<String> = synchronized(sent) { sent.toList() }
    }
}
