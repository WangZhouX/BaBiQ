package com.wzx.huitai.desktop.runtime

import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.KtorAgentTransport
import com.wzx.huitai.security.instance.ProcessInstanceLockException
import com.wzx.huitai.security.secret.JceksSecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

fun interface BusinessAgentSessionLauncher {
    suspend fun launch(request: BusinessAgentLaunchRequest): BusinessAgentRuntimeSession
}

/** Conservative loopback bind check used only after an old authenticated session is unreachable. */
fun interface BusinessBackendPortAvailabilityProbe {
    fun isAvailable(port: Int): Boolean
}

/**
 * Development-only owner for a standalone backend process. It publishes a short-lived,
 * owner-only connection descriptor for a separately started frontend process.
 */
class BusinessBackendDevelopmentRunner(
    home: Path,
    private val backendJar: Path,
    private val port: Int = DEFAULT_PORT,
    private val desktopSecretBootstrap: () -> CharArray = {
        System.getenv(DESKTOP_KEYSTORE_PASSWORD_ENV)
            ?.takeIf(String::isNotBlank)
            ?.toCharArray()
            ?: error("$DESKTOP_KEYSTORE_PASSWORD_ENV is required")
    },
    private val sessionLauncher: BusinessAgentSessionLauncher =
        BusinessAgentSessionLauncher(::launchStandaloneBusinessAgent),
    private val existingSessionProbe: AuthenticatedWebSocketProbe = standaloneAuthenticatedProbe(),
    private val portAvailabilityProbe: BusinessBackendPortAvailabilityProbe = loopbackPortAvailabilityProbe(),
) {
    private val paths = BusinessDesktopRuntimePaths.create(home)

    init {
        require(port in 1..65_535) { "development backend port must be a non-zero TCP port" }
    }

    suspend fun start(): BusinessBackendDevelopmentHandle {
        require(Files.isRegularFile(backendJar)) { "business backend jar is unavailable" }
        val ownership = try {
            BusinessAgentDevelopmentSessionFile.acquireOwnership(paths.agentDevelopmentSession)
        } catch (collision: ProcessInstanceLockException) {
            throw IllegalStateException("development backend is already running", collision)
        }
        var desktopPassword: CharArray? = null
        var backendPassword: CharArray? = null
        var request: BusinessAgentLaunchRequest? = null
        var runtimeSession: BusinessAgentRuntimeSession? = null
        try {
            recoverStaleDevelopmentSession(ownership)
            desktopPassword = desktopSecretBootstrap()
            require(desktopPassword.isNotEmpty()) { "desktop KeyStore password must not be empty" }
            JceksSecretStore(paths.desktopKeyStore, requireNotNull(desktopPassword)).use { store ->
                backendPassword = BusinessBackendKeyStorePasswordVault.loadOrCreate(store)
            }
            val installationId =
                DesktopInstallationIdentityStore(paths.desktopInstallationId).loadOrCreate()
            request = BusinessAgentLaunchRequest.create(
                paths = paths,
                desktopInstanceId = installationId,
                backendJar = backendJar,
                backendKeyStorePassword = requireNotNull(backendPassword),
                port = port,
            )
            runtimeSession = sessionLauncher.launch(request)
            val lease = BusinessAgentDevelopmentSessionFile.publish(
                paths.agentDevelopmentSession,
                request.connectRequest,
                ownership,
            )
            return BusinessBackendDevelopmentHandle(runtimeSession, lease, ownership)
        } catch (failure: Throwable) {
            runCatching { runtimeSession?.close() ?: request?.close() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            runCatching(ownership::close)
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        } finally {
            desktopPassword?.let { Arrays.fill(it, '\u0000') }
            backendPassword?.let { Arrays.fill(it, '\u0000') }
        }
    }

    private suspend fun recoverStaleDevelopmentSession(
        ownership: BusinessAgentDevelopmentSessionOwnership,
    ) {
        val observation = BusinessAgentDevelopmentSessionFile.observeIfExists(
            paths.agentDevelopmentSession,
            ownership,
        ) ?: return
        val authenticated = observation.request?.let { request ->
            try {
                existingSessionProbe.authenticate(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        } ?: false
        check(!authenticated) {
            "development backend is already running"
        }
        check(portAvailabilityProbe.isAvailable(port)) {
            "development backend port $port is already in use"
        }
        BusinessAgentDevelopmentSessionFile.deleteIfUnchanged(observation, ownership)
    }

    override fun toString(): String =
        "BusinessBackendDevelopmentRunner(paths=[REDACTED], jar=[REDACTED], port=$port)"

    companion object {
        const val DEFAULT_PORT = 49_391
        const val DESKTOP_KEYSTORE_PASSWORD_ENV = "HUITAI_DESKTOP_KEYSTORE_PASSWORD"
    }
}

class BusinessBackendDevelopmentHandle internal constructor(
    private val runtimeSession: BusinessAgentRuntimeSession,
    private val sessionLease: BusinessAgentDevelopmentSessionLease,
    private val ownership: BusinessAgentDevelopmentSessionOwnership,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun awaitExit(): Int = runtimeSession.awaitExit()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try {
            sessionLease.close()
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            runtimeSession.close()
        } catch (caught: Throwable) {
            failure?.addSuppressed(caught) ?: run { failure = caught }
        }
        try {
            ownership.close()
        } catch (caught: Throwable) {
            failure?.addSuppressed(caught) ?: run { failure = caught }
        }
        failure?.let { throw it }
    }

    override fun toString(): String =
        "BusinessBackendDevelopmentHandle(runtime=[REDACTED], session=[REDACTED])"
}

private suspend fun launchStandaloneBusinessAgent(
    request: BusinessAgentLaunchRequest,
): BusinessAgentRuntimeSession = BusinessAgentProcessLauncher(
    readinessProbe = BusinessAgentReadinessProbe(standaloneAuthenticatedProbe()),
    output = BusinessAgentProcessOutput.ParentConsole,
).launch(request)

private fun standaloneAuthenticatedProbe(): AuthenticatedWebSocketProbe =
    AuthenticatedWebSocketProbe { request ->
        val client = HttpClient(CIO) { install(WebSockets) }
        val probeJob = SupervisorJob()
        val probeScope = CoroutineScope(probeJob)
        val transport = KtorAgentTransport(client, probeScope)
        var connection: AgentConnection? = null
        var result = false
        try {
            connection = transport.connect(request)
            result = withTimeout(2_000) {
                connection.state.first { it != AgentConnectionState.Connecting }
            } == AgentConnectionState.Connected
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result = false
        } finally {
            withContext(NonCancellable) {
                runCatching { connection?.close() }
                runCatching { transport.close() }
                probeScope.cancel()
                client.close()
            }
        }
        result
    }

internal fun loopbackPortAvailabilityProbe(): BusinessBackendPortAvailabilityProbe =
    BusinessBackendPortAvailabilityProbe { port ->
        try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress("127.0.0.1", port), 1)
            }
            true
        } catch (_: IOException) {
            false
        }
    }

/**
 * Gradle/IDE entry point for the independent backend configuration. The child Spring Boot process
 * inherits this process' console, so all backend logs stay visible in the Backend Run tab.
 */
fun main() {
    val environment = System.getenv()
    val home = environment["HUITAI_DESKTOP_HOME"]
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"))
    val backendJar = System.getProperty("huitai.backend.jar")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?: error("huitai.backend.jar is required")
    val port = environment["HUITAI_BUSINESS_BACKEND_PORT"]
        ?.takeIf(String::isNotBlank)
        ?.toInt()
        ?: BusinessBackendDevelopmentRunner.DEFAULT_PORT
    val runner = BusinessBackendDevelopmentRunner(home, backendJar, port)
    val handle = runBlocking { runner.start() }
    val shutdownHook = Thread({ runCatching(handle::close) }, "business-backend-development-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    try {
        println("Business Backend ready at ws://127.0.0.1:$port/ws/agent")
        val exitCode = handle.awaitExit()
        check(exitCode == 0) { "business backend exited with code $exitCode" }
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        handle.close()
    }
}
