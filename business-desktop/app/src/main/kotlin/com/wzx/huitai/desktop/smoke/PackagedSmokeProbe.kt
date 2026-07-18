package com.wzx.huitai.desktop.smoke

import com.wzx.huitai.security.path.SecureRuntimeFile
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Non-secret evidence collected from the already authenticated packaged runtime. */
data class PackagedSmokeEvidence(
    val profile: String,
    val address: String,
    val port: Int,
    val runtimeRoot: Path,
    val desktopRoot: Path,
    val agentRoot: Path,
    val desktopDatabase: Path,
    val agentDatabase: Path,
    val desktopKeyStore: Path,
    val agentKeyStore: Path,
    val tokenFile: Path,
    val tokenFileDeleted: Boolean,
    val unauthorizedHandshakeRejected: Boolean,
    val authenticatedConnection: Boolean,
    val signedOutIdentityBound: Boolean,
    val childPid: Long,
) {
    companion object {
        const val PROFILE = "business-desktop"
    }
}

/**
 * Writes the single machine-readable result consumed by the extracted-package PowerShell smoke.
 * Token/session/password values are deliberately absent from both the evidence and the report.
 */
class PackagedSmokeProbe(reportPath: Path) {
    private val reportPath = reportPath.toAbsolutePath().normalize()

    fun write(evidence: PackagedSmokeEvidence) {
        val report = validatedReport(evidence)
        SecureRuntimeFile.validateParent(reportPath)
        require(Files.notExists(reportPath, LinkOption.NOFOLLOW_LINKS)) { "packaged smoke report already exists" }
        val encoded = REPORT_JSON.encodeToString(report).toByteArray(Charsets.UTF_8)
        val temporary = Files.createTempFile(reportPath.parent, ".${reportPath.fileName}.", ".tmp")
        try {
            SecureRuntimeFile.openChannel(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(encoded)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, reportPath, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, reportPath)
            }
            SecureRuntimeFile.capture(reportPath)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun toString(): String = "PackagedSmokeProbe(reportPath=[REDACTED])"

    private fun validatedReport(source: PackagedSmokeEvidence): PackagedSmokeReport {
        require(source.profile == BUSINESS_PROFILE) { "packaged smoke must use the business desktop profile" }
        require(source.port in 1..65_535) { "packaged smoke requires a dynamic TCP port" }
        require(InetAddress.getByName(source.address).isLoopbackAddress) {
            "packaged smoke must bind a loopback address"
        }
        require(source.childPid > 0) { "packaged smoke child PID is required" }
        require(source.tokenFileDeleted && Files.notExists(source.tokenFile, LinkOption.NOFOLLOW_LINKS)) {
            "one-shot session token must be deleted"
        }
        require(source.unauthorizedHandshakeRejected) { "unauthorized handshake must be rejected" }
        require(source.authenticatedConnection) { "authenticated Agent connection is required" }
        require(source.signedOutIdentityBound) { "framework signed-out identity must be bound" }

        val runtimeRoot = source.runtimeRoot.normalized()
        val desktopRoot = source.desktopRoot.normalized()
        val agentRoot = source.agentRoot.normalized()
        require(runtimeRoot.fileName.toString() == RUNTIME_DIRECTORY) { "unexpected runtime root" }
        require(desktopRoot == runtimeRoot.resolve("desktop") && agentRoot == runtimeRoot.resolve("agent")) {
            "desktop and Agent runtime roots must be isolated"
        }
        require(desktopRoot != agentRoot) { "desktop and Agent roots must differ" }
        val desktopDatabase = source.desktopDatabase.normalized()
        val agentDatabase = source.agentDatabase.normalized()
        val desktopKeyStore = source.desktopKeyStore.normalized()
        val agentKeyStore = source.agentKeyStore.normalized()
        val tokenFile = source.tokenFile.normalized()
        require(desktopDatabase.startsWith(desktopRoot) && desktopKeyStore.startsWith(desktopRoot)) {
            "desktop data must remain below the desktop root"
        }
        require(agentDatabase.startsWith(agentRoot) && agentKeyStore.startsWith(agentRoot) && tokenFile.startsWith(agentRoot)) {
            "Agent data must remain below the Agent root"
        }
        require(desktopDatabase != agentDatabase && desktopKeyStore != agentKeyStore) {
            "desktop and Agent persistence must be independent"
        }

        return PackagedSmokeReport(
            profile = source.profile,
            address = source.address,
            port = source.port,
            dynamicPort = true,
            loopbackAddress = true,
            runtimeRoot = runtimeRoot.toString(),
            desktopRoot = desktopRoot.toString(),
            agentRoot = agentRoot.toString(),
            desktopDatabase = desktopDatabase.toString(),
            agentDatabase = agentDatabase.toString(),
            desktopKeyStore = desktopKeyStore.toString(),
            agentKeyStore = agentKeyStore.toString(),
            tokenFile = tokenFile.toString(),
            tokenFileDeleted = true,
            unauthorizedHandshakeRejected = true,
            authenticatedConnection = true,
            signedOutIdentityBound = true,
            childPid = source.childPid,
        )
    }

    companion object {
        const val REPORT_ENV = "HUITAI_DESKTOP_SMOKE_REPORT"
        const val BUSINESS_PROFILE = "business-desktop"
        private const val RUNTIME_DIRECTORY = ".huitai-agent-desktop"
        private val REPORT_JSON = Json { encodeDefaults = true }

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): PackagedSmokeProbe? =
            environment[REPORT_ENV]
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.let(::PackagedSmokeProbe)
    }
}

@Serializable
private data class PackagedSmokeReport(
    val profile: String,
    val address: String,
    val port: Int,
    val dynamicPort: Boolean,
    val loopbackAddress: Boolean,
    val runtimeRoot: String,
    val desktopRoot: String,
    val agentRoot: String,
    val desktopDatabase: String,
    val agentDatabase: String,
    val desktopKeyStore: String,
    val agentKeyStore: String,
    val tokenFile: String,
    val tokenFileDeleted: Boolean,
    val unauthorizedHandshakeRejected: Boolean,
    val authenticatedConnection: Boolean,
    val signedOutIdentityBound: Boolean,
    val childPid: Long,
)

private fun Path.normalized(): Path = toAbsolutePath().normalize()
