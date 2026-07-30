package com.wzx.huitai.desktop.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.wzx.huitai.desktop.app.BusinessDesktopCompositionRoot
import com.wzx.huitai.desktop.auth.BusinessAccessGateState
import com.wzx.huitai.desktop.state.BusinessAuthenticationStatus
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal object BusinessRealBackendTestHarness {
    const val account = "task17-desktop@example.test"
    private const val userId = "task17-desktop-user"
    private const val tenantId = "task17-desktop-tenant"
    private const val password = "Task17Password8"
    private val safeEnvironmentKeys = listOf(
        "SystemRoot",
        "WINDIR",
        "TEMP",
        "TMP",
        "TMPDIR",
        "LANG",
        "LC_ALL",
        "PATH",
    )

    fun safeParentEnvironment(oaBaseUrl: String): Map<String, String> =
        safeEnvironmentKeys.mapNotNull { key ->
            System.getenv(key)?.takeIf(String::isNotBlank)?.let { key to it }
        }.toMap() + ("HUITAI_OA_BASE_URL" to oaBaseUrl)

    suspend fun loginReady(root: BusinessDesktopCompositionRoot) {
        val view = requireNotNull(root.runtimeView)
        val login = view.production.loginController
        login.updateAccount(account)
        login.updatePassword(password)
        login.updateAgreement(true)
        login.submit()
        login.completeSlider(true)
        withTimeout(20_000) {
            view.production.authenticationGate.first { gate -> gate == BusinessAccessGateState.READY }
        }
        withTimeout(20_000) {
            view.desktopState.first { state ->
                state.authenticationStatus == BusinessAuthenticationStatus.AUTHENTICATED
            }
        }
        check(login.state.value.password.isEmpty()) { "login password was not cleared" }
    }

    fun assertOaSecretsAbsent(runtimeRoot: Path, vararg requiredArtifacts: Path) {
        val markers = listOf(
            password,
            "task17-desktop-access",
            "task17-desktop-refresh",
        ).map { value -> value.toByteArray(StandardCharsets.US_ASCII) }
        check(Files.isDirectory(runtimeRoot)) { "desktop runtime root is missing" }
        requiredArtifacts.forEach { artifact ->
            check(Files.isRegularFile(artifact)) {
                "required desktop runtime audit artifact is missing"
            }
        }
        Files.walk(runtimeRoot).use { paths ->
            paths.filter(Files::isRegularFile).forEach { path ->
                val content = Files.readAllBytes(path)
                markers.forEach { marker ->
                    check(!content.containsSubsequence(marker)) {
                        "OA secret marker escaped its controlled boundary"
                    }
                }
            }
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        return (0..size - needle.size).any { offset ->
            needle.indices.all { index -> this[offset + index] == needle[index] }
        }
    }

    class FakeOaServer private constructor(
        private val server: HttpServer,
    ) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool(
            Thread.ofPlatform().daemon(true).name("task17-desktop-fake-oa-", 0).factory(),
        )
        private val refreshOrdinal = AtomicInteger()
        private val validAccessTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val validRefreshTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        val baseUrl: String
            get() = "http://${server.address.hostString}:${server.address.port}"

        init {
            validAccessTokens += initialAccessToken()
            validRefreshTokens += initialRefreshToken()
            server.executor = executor
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            try {
                check(exchange.requestHeaders.getFirst("X-Platform-Type") == "pc")
                val response = when (exchange.requestURI.path) {
                    "/law-api/system/auth/get-users-by-mobile" -> tenantCandidates(exchange)
                    "/law-api/system/auth/login" -> login(exchange)
                    "/law-api/system/auth/get-permission-info" -> permissions(exchange)
                    "/law-api/system/auth/refresh-token" -> refresh(exchange)
                    "/law-api/system/auth/logout" -> logout(exchange)
                    else -> error("unexpected fake OA path")
                }
                write(exchange, 200, response)
            } catch (_: Throwable) {
                write(exchange, 500, """{"code":500,"msg":"fake OA contract failure","data":null}""")
            }
        }

        private fun tenantCandidates(exchange: HttpExchange): String {
            check(exchange.requestMethod == "GET")
            check(exchange.requestURI.rawQuery.orEmpty().contains("mobile="))
            return success(
                """[{"userId":"$userId","tenantId":"$tenantId","platformId":2,"tenantName":"Task 17","tenantEnterStatus":0}]""",
            )
        }

        private fun login(exchange: HttpExchange): String {
            check(exchange.requestMethod == "POST")
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            check(body.contains("\"mobileOrEmail\":\"$account\""))
            check(body.contains("\"tenantId\":\"$tenantId\""))
            check(body.contains("\"platformId\":2"))
            check(body.contains("\"password\":"))
            return credential(initialAccessToken(), initialRefreshToken())
        }

        private fun permissions(exchange: HttpExchange): String {
            check(exchange.requestMethod == "GET")
            check(exchange.requestHeaders.getFirst("tenant-id") == tenantId)
            check(bearer(exchange) in validAccessTokens)
            return success(
                """{"permissions":["law:case:query"],"roles":["lawyer"],"user":{"id":"$userId","name":"Task 17 User"},"menus":[]}""",
            )
        }

        private fun refresh(exchange: HttpExchange): String {
            check(exchange.requestMethod == "POST")
            check(exchange.requestHeaders.getFirst("tenant-id") == tenantId)
            check(exchange.requestHeaders.getFirst("Authorization") == null)
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val presented = java.net.URLDecoder.decode(
                body.substringAfter("refreshToken=").substringBefore('&'),
                StandardCharsets.UTF_8,
            )
            check(presented in validRefreshTokens)
            val ordinal = refreshOrdinal.incrementAndGet()
            val accessToken = "task17-desktop-access-$ordinal"
            val refreshToken = "task17-desktop-refresh-$ordinal"
            validAccessTokens += accessToken
            validRefreshTokens += refreshToken
            return credential(accessToken, refreshToken)
        }

        private fun logout(exchange: HttpExchange): String {
            check(exchange.requestMethod == "POST")
            check(exchange.requestHeaders.getFirst("tenant-id") == tenantId)
            check(bearer(exchange) in validAccessTokens)
            return success("true")
        }

        private fun bearer(exchange: HttpExchange): String =
            requireNotNull(exchange.requestHeaders.getFirst("Authorization"))
                .removePrefix("Bearer ")

        private fun credential(accessToken: String, refreshToken: String): String = success(
            """{"accessToken":"$accessToken","refreshToken":"$refreshToken","userId":"$userId","expiresTime":9999999999}""",
        )

        private fun success(data: String): String = """{"code":0,"msg":"","data":$data}"""

        private fun write(exchange: HttpExchange, status: Int, response: String) {
            runCatching {
                val bytes = response.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { body -> body.write(bytes) }
            }
            exchange.close()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            validAccessTokens.clear()
            validRefreshTokens.clear()
        }

        override fun toString(): String = "FakeOaServer(baseUrl=[REDACTED], credentials=[REDACTED])"

        companion object {
            fun start(): FakeOaServer {
                val server = HttpServer.create(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    0,
                )
                return FakeOaServer(server)
            }

            private fun initialAccessToken(): String = "task17-desktop-access-initial"
            private fun initialRefreshToken(): String = "task17-desktop-refresh-initial"
        }
    }
}
