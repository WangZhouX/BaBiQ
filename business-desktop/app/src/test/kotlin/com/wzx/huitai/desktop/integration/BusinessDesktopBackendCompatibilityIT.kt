package com.wzx.huitai.desktop.integration

import com.wzx.huitai.agent.client.AgentConnectRequest
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import com.wzx.huitai.agent.client.KtorAgentTransport
import com.wzx.huitai.desktop.app.BusinessAgentChildHandle
import com.wzx.huitai.desktop.app.BusinessAgentChildLauncher
import com.wzx.huitai.desktop.app.BusinessAgentConnectionHandle
import com.wzx.huitai.desktop.app.BusinessAgentConnector
import com.wzx.huitai.desktop.app.BusinessDesktopCompositionRoot
import com.wzx.huitai.desktop.app.BusinessDesktopProductionConfiguration
import com.wzx.huitai.desktop.app.CompositionResource
import com.wzx.huitai.desktop.app.DesktopSecretBootstrap
import com.wzx.huitai.desktop.app.ProductionBusinessDesktopCompositionFactory
import com.wzx.huitai.desktop.logging.DesktopLoggingBootstrap
import com.wzx.huitai.desktop.runtime.AuthenticatedWebSocketProbe
import com.wzx.huitai.desktop.runtime.BusinessAgentLaunchRequest
import com.wzx.huitai.desktop.runtime.BusinessAgentProcessLauncher
import com.wzx.huitai.desktop.runtime.BusinessAgentReadinessProbe
import com.wzx.huitai.desktop.runtime.BusinessAgentRuntimeSession
import com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths
import com.wzx.huitai.desktop.runtime.ManagedBusinessAgentConnection
import com.wzx.huitai.desktop.state.BusinessAuthenticationStatus
import com.wzx.huitai.desktop.state.BusinessConnectionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class BusinessDesktopBackendCompatibilityIT {
    @Test
    fun `真实内置Java后端与生产Ktor客户端完成认证注册作用域重连隔离和关闭`() = runBlocking {
        DesktopLoggingBootstrap.resetForTests()
        val backendJar = Path.of(requireNotNull(System.getProperty("huitai.backend.jar")))
            .toAbsolutePath()
            .normalize()
        assertTrue(Files.isRegularFile(backendJar), "backend jar must be built before compatibility IT")

        val home = Files.createTempDirectory("business-desktop-backend-compatibility-it")
        val workspace = Files.createDirectories(home.resolve("workspace"))
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val capturedSession = AtomicReference<BusinessAgentRuntimeSession>()
        val capturedPaths = AtomicReference<BusinessDesktopRuntimePaths>()
        val capturedConnection = AtomicReference<ManagedBusinessAgentConnection>()

        val childLauncher = BusinessAgentChildLauncher { context ->
            capturedPaths.set(context.paths)
            val request = BusinessAgentLaunchRequest.create(
                paths = context.paths,
                desktopInstanceId = context.desktopInstanceId,
                backendJar = context.backendJar,
                backendKeyStorePassword = context.backendKeyStorePassword,
            )
            val session = BusinessAgentProcessLauncher(
                readinessProbe = authenticatedReadinessProbe(),
            ).launch(request)
            capturedSession.set(session)
            BusinessAgentChildHandle(
                identity = session.identity,
                sequenceTracker = session.sequenceTracker,
                resource = CompositionResource { session.close() },
                runtimeSession = session,
            )
        }
        val connector = BusinessAgentConnector {
            val session = requireNotNull(capturedSession.get())
            val client = HttpClient(CIO) { install(WebSockets) }
            val transport = KtorAgentTransport(client, testScope)
            try {
                val connection = session.connect(transport, testScope)
                val managed = connection as ManagedBusinessAgentConnection
                capturedConnection.set(managed)
                BusinessAgentConnectionHandle(
                    connection = managed,
                    resource = CompositionResource {
                        withContext(NonCancellable) {
                            runCatching { managed.close() }
                            runCatching { transport.close() }
                            client.close()
                        }
                    },
                )
            } catch (failure: Throwable) {
                runCatching { transport.close() }
                client.close()
                throw failure
            }
        }
        val factory = ProductionBusinessDesktopCompositionFactory(
            configuration = BusinessDesktopProductionConfiguration(
                home = home,
                backendJar = backendJar,
                desktopSecretBootstrap = DesktopSecretBootstrap { "compatibility-secret".toCharArray() },
                frameworkDemoIdentity = true,
            ),
            parentScope = testScope,
            childLauncher = childLauncher,
            connector = connector,
        )

        var root: BusinessDesktopCompositionRoot? = null
        var childPid: Long? = null
        try {
            root = BusinessDesktopCompositionRoot.start(factory)
            val session = requireNotNull(capturedSession.get())
            val paths = requireNotNull(capturedPaths.get())
            val managed = requireNotNull(capturedConnection.get())
            childPid = session.childPid

            assertTrue(session.isAlive)
            assertFalse(Files.exists(paths.agentSessionToken))
            assertTrue(Files.isRegularFile(paths.agentDatabase))
            assertTrue(Files.isRegularFile(paths.desktopDatabase))
            assertTrue(paths.agentDatabase.startsWith(paths.agentRoot))
            assertTrue(paths.desktopDatabase.startsWith(paths.desktopRoot))
            assertFalse(paths.agentRoot.startsWith(paths.desktopRoot))
            assertFalse(paths.desktopRoot.startsWith(paths.agentRoot))

            val view = assertNotNull(root.runtimeView)
            assertEquals(BusinessAuthenticationStatus.AUTHENTICATED, view.desktopState.value.authenticationStatus)
            assertEquals(BusinessConnectionStatus.CONNECTED, view.desktopState.value.connectionStatus)
            val firstThread = view.production.businessAgentClient.createThread(workspace.toString())
            assertEquals(workspace.toAbsolutePath().normalize().toString(), Path.of(firstThread.cwd).toAbsolutePath().normalize().toString())

            assertTrue(authenticationRejected(session.connectRequest, token = "invalid-token"))
            assertTrue(authenticationRejected(
                session.connectRequest,
                desktopSessionId = "old-session-${session.identity.desktopSessionId}",
            ))

            val oldConnectionId = managed.connectionId
            assertTrue(managed.reconnect(oldConnectionId))
            withTimeout(COMPATIBILITY_TIMEOUT) {
                managed.supervisorState.first { state ->
                    state is AgentSupervisorState.Connected && state.connectionId != oldConnectionId
                }
            }
            withTimeout(COMPATIBILITY_TIMEOUT) {
                view.desktopState.first { state ->
                    state.connectionStatus == BusinessConnectionStatus.CONNECTED &&
                        managed.connectionId != oldConnectionId
                }
            }
            assertNotEquals(oldConnectionId, managed.connectionId)
            val secondThread = view.production.businessAgentClient.createThread(workspace.toString())
            assertNotEquals(firstThread.id, secondThread.id)
            assertEquals(firstThread.cwd, secondThread.cwd)
        } finally {
            withContext(NonCancellable) { root?.shutdown() }
            testScope.cancel()
            DesktopLoggingBootstrap.resetForTests()
        }

        assertFalse(requireNotNull(capturedSession.get()).isAlive)
        assertFalse(ProcessHandle.of(requireNotNull(childPid)).map { it.isAlive }.orElse(false))
    }

    private fun authenticatedReadinessProbe() = BusinessAgentReadinessProbe(
        authenticator = AuthenticatedWebSocketProbe { request ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = HttpClient(CIO) { install(WebSockets) }
            val transport = KtorAgentTransport(client, scope)
            var connection: AgentConnection? = null
            try {
                connection = transport.connect(request)
                withTimeout(2_000) {
                    connection.state.first { state -> state != AgentConnectionState.Connecting }
                } == AgentConnectionState.Connected
            } catch (_: Exception) {
                false
            } finally {
                withContext(NonCancellable) {
                    runCatching { connection?.close() }
                    runCatching { transport.close() }
                    scope.cancel()
                    client.close()
                }
            }
        },
        timeoutMillis = 30_000,
    )

    private suspend fun authenticationRejected(
        valid: AgentConnectRequest,
        token: String = valid.identity.desktopSessionToken,
        desktopSessionId: String = valid.identity.desktopSessionId,
    ): Boolean {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(CIO) { install(WebSockets) }
        val transport = KtorAgentTransport(client, scope)
        var connection: AgentConnection? = null
        return try {
            val identity = DesktopSessionIdentity(
                desktopInstanceId = valid.identity.desktopInstanceId,
                desktopSessionId = desktopSessionId,
                desktopSessionToken = token,
                localOrigin = valid.identity.localOrigin,
            )
            connection = transport.connect(AgentConnectRequest(valid.url, identity))
            withTimeout(5_000) {
                connection.state.first { state -> state != AgentConnectionState.Connecting }
            } == AgentConnectionState.AuthenticationFailed
        } finally {
            withContext(NonCancellable) {
                runCatching { connection?.close() }
                runCatching { transport.close() }
                scope.cancel()
                client.close()
            }
        }
    }

    private companion object {
        const val COMPATIBILITY_TIMEOUT = 20_000L
    }
}
