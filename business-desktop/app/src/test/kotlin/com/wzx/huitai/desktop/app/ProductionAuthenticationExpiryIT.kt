package com.wzx.huitai.desktop.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.agent.client.ApplicationSequenceTracker
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import com.wzx.huitai.desktop.auth.BusinessAccessGateState
import com.wzx.huitai.desktop.logging.DesktopLoggingBootstrap
import com.wzx.huitai.desktop.runtime.ManagedBusinessAgentConnection
import com.wzx.huitai.desktop.state.BusinessAuthenticationStatus
import com.wzx.huitai.integration.http.HuitaiRequest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProductionAuthenticationExpiryIT {
    @AfterTest
    fun resetLogging() {
        DesktopLoggingBootstrap.resetForTests()
    }

    @Test
    fun `real production HTTP refresh failure revokes registry and workspace`() =
        runExpiryScenario(RefreshBehavior.FAILURE)

    @Test
    fun `real production HTTP permission drift consumes response revokes actions and permits relogin`() =
        runExpiryScenario(RefreshBehavior.ADDED_PERMISSION)

    @Test
    fun `canceled only HTTP waiter cannot leave permission drift owner ready`() =
        runExpiryScenario(RefreshBehavior.BLOCKED_ADDED_PERMISSION, cancelHttpWaiter = true)

    private fun runExpiryScenario(
        refreshBehavior: RefreshBehavior,
        cancelHttpWaiter: Boolean = false,
    ) = runBlocking {
        LoopbackOaServer(refreshBehavior).use { oa ->
            val home = Files.createTempDirectory("huitai-production-auth-expiry")
            writeOaConfiguration(home, oa.baseUrl)
            val connection = AutoRespondingAgentConnection()
            val root = BusinessDesktopCompositionRoot.start(
                ProductionBusinessDesktopCompositionFactory(
                    configuration = BusinessDesktopProductionConfiguration(
                        home = home,
                        backendJar = home.resolve("backend/babiq-server.jar"),
                        desktopSecretBootstrap = DesktopSecretBootstrap {
                            "production-auth-expiry-password".toCharArray()
                        },
                        frameworkDemoIdentity = false,
                    ),
                    parentScope = this,
                    childLauncher = BusinessAgentChildLauncher { context ->
                        val identity = DesktopSessionIdentity.forChildLaunch(
                            context.desktopInstanceId,
                            "http://127.0.0.1",
                        )
                        BusinessAgentChildHandle(
                            identity = identity,
                            sequenceTracker = ApplicationSequenceTracker(identity.desktopSessionId),
                            resource = CompositionResource {},
                        )
                    },
                    connector = BusinessAgentConnector {
                        BusinessAgentConnectionHandle(
                            connection,
                            CompositionResource { connection.close() },
                        )
                    },
                ),
            )
            try {
                val view = requireNotNull(root.runtimeView)
                val production = view.production
                withTimeout(5_000) {
                    production.authenticationGate.first { it == BusinessAccessGateState.SIGNED_OUT }
                }

                login(production)

                val ready = withTimeout(5_000) {
                    production.identityRegistry.snapshot.first { it.gate == BusinessAccessGateState.READY }
                }
                val oldIdentity = requireNotNull(ready.identity)
                assertTrue(production.workspaceController.hasActiveIdentity)
                assertEquals(oldIdentity, view.desktopState.value.identity)

                val sendProtectedRequest: suspend () -> Any? = {
                    production.authenticatedHttpClient.send(
                        HuitaiRequest(
                            method = "GET",
                            relativePath = "/business/protected",
                            headers = emptyMap(),
                            body = ByteArray(0),
                            replayPolicy = ActionReplayPolicy.SAFE,
                            executionId = null,
                            idempotencyHeaderName = null,
                            reconciliationPolicy = ReconciliationPolicy.NONE,
                        ),
                    )
                }
                val response = if (cancelHttpWaiter) {
                    val waiter = async(start = CoroutineStart.UNDISPATCHED) { sendProtectedRequest() }
                    try {
                        assertTrue(
                            withContext(Dispatchers.IO) { oa.awaitRefreshStarted() },
                            "refresh owner must reach the blocked endpoint",
                        )
                        waiter.cancel()
                        waiter.join()
                    } finally {
                        oa.releaseRefresh()
                    }
                    withTimeout(5_000) {
                        production.authenticationGate.first { it == BusinessAccessGateState.SIGNED_OUT }
                    }
                    null
                } else {
                    withTimeout(5_000) { sendProtectedRequest() }
                }

                assertNull(response, "expiry callback must consume the stale response")
                assertEquals(1, oa.protectedRequestCount.get())
                assertEquals(1, oa.refreshRequestCount.get())
                assertEquals(
                    if (refreshBehavior == RefreshBehavior.FAILURE) 1 else 2,
                    oa.permissionRequestCount.get(),
                )
                assertTrue(oa.observedTenantHeaders.all { it == TENANT_ID })
                assertEquals(BusinessAccessGateState.SIGNED_OUT, production.authenticationGate.value)
                assertNull(production.identityRegistry.snapshot.value.identity)
                assertFalse(production.workspaceController.hasActiveIdentity)
                assertNull(view.desktopState.value.identity)
                assertNull(view.desktopState.value.page)
                assertEquals(BusinessAuthenticationStatus.SIGNED_OUT, view.desktopState.value.authenticationStatus)

                val actionFailure = runCatching {
                    withTimeout(1_000) {
                        production.workspaceController.executeUserAction(
                            executionId = "execution-after-expiry",
                            actionId = "save_form_draft",
                            actionVersion = 1,
                            input = buildJsonObject { },
                        )
                    }
                }.exceptionOrNull()
                assertIs<IllegalArgumentException>(actionFailure)

                login(production)
                val relogged = withTimeout(5_000) {
                    production.identityRegistry.snapshot.first {
                        it.gate == BusinessAccessGateState.READY &&
                            it.identity?.authSessionId != oldIdentity.authSessionId
                    }
                }
                assertEquals(USER_ID, relogged.identity?.userId)
            } finally {
                root.shutdown()
            }
        }
    }

    private suspend fun login(production: ProductionUiComponents) {
        production.loginController.updateAccount(MOBILE)
        production.loginController.updatePassword("password8")
        production.loginController.updateAgreement(true)
        production.loginController.updateRemember(false)
        production.loginController.submit()
        production.loginController.completeSlider(success = true)
    }

    private fun writeOaConfiguration(home: java.nio.file.Path, baseUrl: String) {
        val configuration = home.resolve(".huitai-agent-desktop/desktop/config/business-desktop.properties")
        Files.createDirectories(configuration.parent)
        Files.writeString(
            configuration,
            """
            huitai.oa.base-url=$baseUrl
            huitai.oa.api-prefix=/api
            huitai.oa.platform-id=1
            huitai.oa.request-timeout-ms=5000
            huitai.oa.service-agreement-url=$baseUrl/agreement
            huitai.oa.privacy-policy-url=$baseUrl/privacy
            huitai.oa.allow-insecure-http=true
            """.trimIndent(),
        )
    }

    private class LoopbackOaServer(
        private val refreshBehavior: RefreshBehavior,
    ) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            0,
        )
        private val refreshed = AtomicBoolean(false)
        private val refreshStarted = CountDownLatch(1)
        private val refreshRelease = CountDownLatch(
            if (refreshBehavior == RefreshBehavior.BLOCKED_ADDED_PERMISSION) 1 else 0,
        )
        val protectedRequestCount = AtomicInteger()
        val refreshRequestCount = AtomicInteger()
        val permissionRequestCount = AtomicInteger()
        val observedTenantHeaders = CopyOnWriteArrayList<String?>()
        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/api", ::handle)
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            exchange.requestBody.use { it.readAllBytes() }
            val tenant = exchange.requestHeaders.getFirst("tenant-id")
            if (tenant != null) observedTenantHeaders += tenant
            when (exchange.requestURI.path) {
                "/api/system/auth/get-users-by-mobile" -> success(
                    exchange,
                    """[{"userId":"$USER_ID","tenantId":"$TENANT_ID","platformId":1,"tenantName":"测试律所","tenantEnterStatus":0,"tenantEnterId":"member-1"}]""",
                )
                "/api/system/auth/login" -> success(
                    exchange,
                    """{"accessToken":"access-old","refreshToken":"refresh-old","userId":"$USER_ID","expiresTime":4102444800000}""",
                )
                "/api/system/auth/get-permission-info" -> {
                    permissionRequestCount.incrementAndGet()
                    val permissions = if (refreshed.get()) {
                        """["demo.write","demo.submit","demo.admin"]"""
                    } else {
                        """["demo.write","demo.submit"]"""
                    }
                    success(
                        exchange,
                        """{"permissions":$permissions,"roles":["lawyer"],"user":{"id":"$USER_ID","name":"测试律师"},"menus":[]}""",
                    )
                }
                "/api/system/auth/refresh-token" -> {
                    refreshRequestCount.incrementAndGet()
                    refreshStarted.countDown()
                    check(refreshRelease.await(5, TimeUnit.SECONDS)) { "refresh release timed out" }
                    if (refreshBehavior == RefreshBehavior.FAILURE) {
                        respond(exchange, 500, """{"code":500,"msg":"refresh failed","data":{}}""")
                    } else {
                        refreshed.set(true)
                        success(
                            exchange,
                            """{"accessToken":"access-new","refreshToken":"refresh-new","userId":"$USER_ID","expiresTime":4102444800000}""",
                        )
                    }
                }
                "/api/system/auth/logout" -> success(exchange, "{}")
                "/api/business/protected" -> {
                    protectedRequestCount.incrementAndGet()
                    respond(exchange, 401, """{"code":401,"msg":"expired","data":{}}""")
                }
                else -> respond(exchange, 404, """{"code":404,"msg":"not found","data":{}}""")
            }
        }

        private fun success(exchange: HttpExchange, data: String) =
            respond(exchange, 200, """{"code":200,"msg":"ok","data":$data}""")

        private fun respond(exchange: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        fun awaitRefreshStarted(): Boolean = refreshStarted.await(5, TimeUnit.SECONDS)

        fun releaseRefresh() {
            refreshRelease.countDown()
        }

        override fun close() {
            refreshRelease.countDown()
            server.stop(0)
        }
    }

    private class AutoRespondingAgentConnection : ManagedBusinessAgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        override val connectionId: String = "production-auth-expiry-connection"
        override val incoming: ReceiveChannel<String> = incomingChannel
        override val state: StateFlow<AgentConnectionState> =
            MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val supervisorState: StateFlow<AgentSupervisorState> =
            MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connected(connectionId))
        override val hasConnected: Boolean = true
        override suspend fun send(text: String) {
            val request = Json.parseToJsonElement(text).jsonObject
            val id = request["id"]?.jsonPrimitive?.content ?: return
            val method = request["method"]?.jsonPrimitive?.content
            val result = if (method == "provider/list") {
                """{"providers":[]}"""
            } else {
                "{}"
            }
            incomingChannel.send("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}")
        }

        override suspend fun close() {
            incomingChannel.close()
        }
        override suspend fun manualRetry(): Boolean = true
        override suspend fun reconnect(expectedConnectionId: String): Boolean = expectedConnectionId == connectionId
    }

    private enum class RefreshBehavior {
        FAILURE,
        ADDED_PERMISSION,
        BLOCKED_ADDED_PERMISSION,
    }

    private companion object {
        const val MOBILE = "13800008888"
        const val USER_ID = "user-1"
        const val TENANT_ID = "tenant-1"
    }
}
