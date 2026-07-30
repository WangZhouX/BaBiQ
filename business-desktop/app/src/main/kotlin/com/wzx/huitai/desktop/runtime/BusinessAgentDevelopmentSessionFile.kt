package com.wzx.huitai.desktop.runtime

import com.wzx.huitai.agent.client.AgentConnectRequest
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import com.wzx.huitai.desktop.auth.config.BusinessBackendConnectionConfiguration
import com.wzx.huitai.security.instance.ProcessInstanceLock
import com.wzx.huitai.security.path.SecureRuntimeFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 开发态独立后端与前端之间的短生命周期认证会话文件。
 *
 * 文件只在业务后端存活期间存在，使用 owner-only 权限；公开字符串不会输出路径或认证字段。
 */
object BusinessAgentDevelopmentSessionFile {
    private const val MAX_FILE_BYTES = 8_192L
    private const val WEBSOCKET_PATH = "/ws/agent"
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun acquireOwnership(path: Path): BusinessAgentDevelopmentSessionOwnership {
        val normalized = normalize(path)
        val lockPath = normalized.resolveSibling("${normalized.fileName}.lock")
        return BusinessAgentDevelopmentSessionOwnership(
            normalized,
            ProcessInstanceLock.acquire(lockPath),
        )
    }

    fun publish(
        path: Path,
        request: AgentConnectRequest,
        ownership: BusinessAgentDevelopmentSessionOwnership,
    ): BusinessAgentDevelopmentSessionLease {
        val normalized = normalize(path)
        ownership.requireOwned(normalized)
        val payload = DevelopmentSessionPayload.from(request).also(::validate)
        val encoded = json.encodeToString(DevelopmentSessionPayload.serializer(), payload)
            .toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_FILE_BYTES) { "development session payload is too large" }
        SecureRuntimeFile.validateParent(normalized)
        require(Files.notExists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            "development session already exists"
        }
        try {
            SecureRuntimeFile.openChannel(
                normalized,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(encoded)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            RuntimeFilePermissions.applyOwnerOnly(normalized, directory = false)
            val identity = SecureRuntimeFile.capture(normalized)
            val fingerprint = contentFingerprint(normalized)
            return BusinessAgentDevelopmentSessionLease(
                normalized,
                identity,
                fingerprint,
                ownership,
            )
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(normalized) }
            throw failure
        } finally {
            encoded.fill(0)
        }
    }

    fun read(path: Path): AgentConnectRequest {
        val normalized = normalize(path)
        val identity = SecureRuntimeFile.capture(normalized)
        val bytes = SecureRuntimeFile.openChannel(normalized, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            require(size in 1..MAX_FILE_BYTES) { "development session file has an invalid size" }
            val data = ByteArray(size.toInt())
            val buffer = ByteBuffer.wrap(data)
            while (buffer.hasRemaining()) {
                check(channel.read(buffer) >= 0) { "development session file ended unexpectedly" }
            }
            data
        }
        try {
            SecureRuntimeFile.verifyUnchanged(identity)
            val payload = try {
                json.decodeFromString(
                    DevelopmentSessionPayload.serializer(),
                    bytes.toString(StandardCharsets.UTF_8),
                )
            } catch (failure: Exception) {
                throw IllegalArgumentException("development session file is invalid", failure)
            }
            validate(payload)
            return payload.toConnectRequest()
        } finally {
            bytes.fill(0)
        }
    }

    fun read(
        path: Path,
        expectedConfiguration: BusinessBackendConnectionConfiguration,
    ): AgentConnectRequest {
        val request = read(path)
        require(request.url == expectedConfiguration.websocketUrl) {
            "development backend URL does not match desktop configuration"
        }
        require(request.identity.localOrigin == expectedConfiguration.localOrigin) {
            "development backend Origin does not match desktop configuration"
        }
        return AgentConnectRequest(
            url = expectedConfiguration.websocketUrl,
            identity = DesktopSessionIdentity(
                desktopInstanceId = request.identity.desktopInstanceId,
                desktopSessionId = request.identity.desktopSessionId,
                desktopSessionToken = request.identity.desktopSessionToken,
                localOrigin = expectedConfiguration.localOrigin,
            ),
        )
    }

    /**
     * IDEA Compound 启动时后端和前端并行拉起；在有限时间内等待后端发布握手文件。
     */
    suspend fun awaitRead(
        path: Path,
        expectedConfiguration: BusinessBackendConnectionConfiguration,
        timeoutMillis: Long = 30_000L,
    ): AgentConnectRequest = withTimeoutOrNull(timeoutMillis) {
        var loaded: AgentConnectRequest? = null
        while (loaded == null) {
            loaded = try {
                read(path, expectedConfiguration)
            } catch (failure: IllegalArgumentException) {
                null
            } catch (failure: CancellationException) {
                throw failure
            }
            if (loaded == null) {
                delay(100)
            }
        }
        loaded
    } ?: throw IllegalStateException("development backend session was not published in time")

    /**
     * Captures one stable view for startup recovery. A null [BusinessAgentDevelopmentSessionObservation.request]
     * means the regular owner-controlled file exists but its bounded payload is invalid.
     */
    internal fun observeIfExists(
        path: Path,
        ownership: BusinessAgentDevelopmentSessionOwnership,
    ): BusinessAgentDevelopmentSessionObservation? {
        val normalized = normalize(path)
        ownership.requireOwned(normalized)
        val identity = SecureRuntimeFile.captureIfExists(normalized) ?: return null
        val fingerprint = contentFingerprint(normalized)
        val request = try {
            read(normalized)
        } catch (_: IllegalArgumentException) {
            null
        }
        SecureRuntimeFile.verifyUnchanged(identity)
        return BusinessAgentDevelopmentSessionObservation(normalized, identity, fingerprint, request)
    }

    /** Deletes only the exact leaf captured by [observeIfExists]; a replacement is never removed. */
    internal fun deleteIfUnchanged(
        observation: BusinessAgentDevelopmentSessionObservation,
        ownership: BusinessAgentDevelopmentSessionOwnership,
    ) {
        ownership.requireOwned(observation.path)
        deleteIfUnchanged(observation.path, observation.identity, observation.fingerprint)
    }

    private fun deleteIfUnchanged(
        path: Path,
        identity: SecureRuntimeFile.Identity,
        expectedFingerprint: BusinessAgentDevelopmentSessionFingerprint?,
    ) {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return
        SecureRuntimeFile.verifyUnchanged(identity)
        if (expectedFingerprint != null) {
            require(expectedFingerprint.matches(contentFingerprint(path))) {
                "development session changed during recovery"
            }
        }
        Files.deleteIfExists(path)
    }

    private fun contentFingerprint(path: Path): BusinessAgentDevelopmentSessionFingerprint =
        SecureRuntimeFile.openChannel(path, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(FINGERPRINT_BUFFER_BYTES)
            var remaining = minOf(size, MAX_FILE_BYTES + 1)
            while (remaining > 0) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                val read = channel.read(buffer)
                check(read >= 0) { "development session file ended unexpectedly" }
                buffer.flip()
                digest.update(buffer)
                remaining -= read
            }
            BusinessAgentDevelopmentSessionFingerprint(size, digest.digest())
        }

    private fun normalize(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.fileName != null && normalized.parent != null) {
            "development session path must identify a file"
        }
        return normalized
    }

    private fun validate(payload: DevelopmentSessionPayload) {
        val uri = runCatching { URI(payload.url) }
            .getOrElse { throw IllegalArgumentException("development backend URL is invalid", it) }
        require(uri.scheme.equals("ws", ignoreCase = true)) {
            "development backend must use ws"
        }
        require(uri.host == "127.0.0.1" && uri.port in 1..65_535 && uri.path == WEBSOCKET_PATH) {
            "development backend must use the fixed loopback WebSocket endpoint"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "development backend URL contains unsupported components"
        }
        validateUuid(payload.desktopInstanceId, "desktopInstanceId")
        validateUuid(payload.desktopSessionId, "desktopSessionId")
        require(isValidToken(payload.desktopSessionToken)) {
            "development desktop session token is invalid"
        }
        require(payload.localOrigin == "http://127.0.0.1:${uri.port}") {
            "development local origin must match the loopback backend port"
        }
    }

    private fun validateUuid(value: String, name: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
            "development $name is invalid"
        }
    }

    private fun isValidToken(value: String): Boolean =
        value.length == 43 && runCatching {
            Base64.getUrlDecoder().decode(value).size == 32
        }.getOrDefault(false)

    private const val FINGERPRINT_BUFFER_BYTES = 4_096

    @Serializable
    private data class DevelopmentSessionPayload(
        val url: String,
        val desktopInstanceId: String,
        val desktopSessionId: String,
        val desktopSessionToken: String,
        val localOrigin: String,
    ) {
        fun toConnectRequest(): AgentConnectRequest = AgentConnectRequest(
            url = url,
            identity = DesktopSessionIdentity(
                desktopInstanceId = desktopInstanceId,
                desktopSessionId = desktopSessionId,
                desktopSessionToken = desktopSessionToken,
                localOrigin = localOrigin,
            ),
        )

        companion object {
            fun from(request: AgentConnectRequest) = DevelopmentSessionPayload(
                url = request.url,
                desktopInstanceId = request.identity.desktopInstanceId,
                desktopSessionId = request.identity.desktopSessionId,
                desktopSessionToken = request.identity.desktopSessionToken,
                localOrigin = request.identity.localOrigin,
            )
        }
    }
}

internal class BusinessAgentDevelopmentSessionObservation internal constructor(
    internal val path: Path,
    internal val identity: SecureRuntimeFile.Identity,
    internal val fingerprint: BusinessAgentDevelopmentSessionFingerprint,
    val request: AgentConnectRequest?,
)

class BusinessAgentDevelopmentSessionOwnership internal constructor(
    private val sessionPath: Path,
    private val processLock: ProcessInstanceLock,
) : AutoCloseable {
    private val monitor = Any()
    @Volatile
    private var closed = false

    internal fun requireOwned(path: Path) {
        check(!closed && path == sessionPath) {
            "development session ownership is unavailable"
        }
    }

    override fun close() = synchronized(monitor) {
        if (closed) return@synchronized
        processLock.close()
        closed = true
    }

    override fun toString(): String = "BusinessAgentDevelopmentSessionOwnership(path=[REDACTED])"
}

internal class BusinessAgentDevelopmentSessionFingerprint internal constructor(
    private val size: Long,
    private val digest: ByteArray,
) {
    fun matches(other: BusinessAgentDevelopmentSessionFingerprint): Boolean =
        size == other.size && MessageDigest.isEqual(digest, other.digest)

    override fun toString(): String = "BusinessAgentDevelopmentSessionFingerprint([REDACTED])"
}

class BusinessAgentDevelopmentSessionLease internal constructor(
    private val path: Path,
    private val identity: SecureRuntimeFile.Identity,
    private val fingerprint: BusinessAgentDevelopmentSessionFingerprint,
    private val ownership: BusinessAgentDevelopmentSessionOwnership,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        BusinessAgentDevelopmentSessionFile.deleteIfUnchanged(
            BusinessAgentDevelopmentSessionObservation(path, identity, fingerprint, request = null),
            ownership,
        )
    }

    override fun toString(): String = "BusinessAgentDevelopmentSessionLease(path=[REDACTED])"
}
