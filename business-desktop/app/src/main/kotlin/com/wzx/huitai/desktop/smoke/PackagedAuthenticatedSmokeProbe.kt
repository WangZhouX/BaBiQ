package com.wzx.huitai.desktop.smoke

import com.wzx.huitai.security.path.SecureRuntimeFile
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.net.InetAddress
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Non-sensitive result of running the authenticated diagnostic entry point from an extracted
 * desktop package. Remote credentials and OA payloads are deliberately not representable here.
 */
data class PackagedAuthenticatedSmokeEvidence(
    val profile: String,
    val oaLoopback: Boolean,
    val ready: Boolean,
    val identityEpoch: Long,
    val workbenchReady: Boolean,
    val workbenchSections: Set<String>,
    val navigationAllowlisted: Boolean,
    val assistantControllerReady: Boolean,
)

data class PackagedWorkbenchCheck(
    val sections: Set<String>,
    val navigationAllowlisted: Boolean,
)

/** Small seam that keeps the package-smoke workflow unit-testable without replacing production composition. */
interface PackagedAuthenticatedSmokeRuntime {
    suspend fun authenticate()
    suspend fun awaitReady(): Long
    suspend fun loadWorkbench(identityEpoch: Long): PackagedWorkbenchCheck
    suspend fun verifyAssistantController(): Boolean
}

class PackagedAuthenticatedSmokeWorkflow(
    oaBaseUrl: String,
    private val runtime: PackagedAuthenticatedSmokeRuntime,
) {
    private val oaUri = URI.create(oaBaseUrl)

    init {
        require(oaUri.scheme == "http" && oaUri.rawUserInfo == null && oaUri.rawQuery == null) {
            "packaged smoke OA must be a credential-free HTTP URI"
        }
        require(InetAddress.getByName(oaUri.host).isLoopbackAddress) {
            "packaged smoke OA must use loopback"
        }
    }

    suspend fun run(): PackagedAuthenticatedSmokeEvidence {
        runtime.authenticate()
        val identityEpoch = runtime.awaitReady()
        val workbench = runtime.loadWorkbench(identityEpoch)
        val assistantReady = runtime.verifyAssistantController()
        return PackagedAuthenticatedSmokeEvidence(
            profile = PackagedSmokeEvidence.PROFILE,
            oaLoopback = true,
            ready = true,
            identityEpoch = identityEpoch,
            workbenchReady = workbench.sections.containsAll(PackagedAuthenticatedSmokeProbe.REQUIRED_SECTIONS),
            workbenchSections = workbench.sections,
            navigationAllowlisted = workbench.navigationAllowlisted,
            assistantControllerReady = assistantReady,
        )
    }
}

/** Writes authenticated packaged-smoke evidence once through the hardened runtime-file boundary. */
class PackagedAuthenticatedSmokeProbe(reportPath: Path) {
    private val reportPath = reportPath.toAbsolutePath().normalize()

    fun write(evidence: PackagedAuthenticatedSmokeEvidence) {
        val report = validate(evidence)
        SecureRuntimeFile.validateParent(reportPath)
        require(Files.notExists(reportPath)) { "authenticated packaged smoke report already exists" }
        val encoded = JSON.encodeToString(report).toByteArray(Charsets.UTF_8)
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

    override fun toString(): String = "PackagedAuthenticatedSmokeProbe(reportPath=[REDACTED])"

    private fun validate(source: PackagedAuthenticatedSmokeEvidence): PackagedAuthenticatedSmokeReport {
        require(source.profile == PackagedSmokeEvidence.PROFILE) {
            "authenticated packaged smoke must use the business desktop profile"
        }
        require(source.oaLoopback) { "authenticated packaged smoke OA must be loopback" }
        require(source.ready && source.identityEpoch > 0) {
            "authenticated packaged smoke must reach READY"
        }
        require(source.workbenchReady && source.workbenchSections.containsAll(REQUIRED_SECTIONS)) {
            "authenticated packaged smoke requires every workbench section"
        }
        require(source.navigationAllowlisted) {
            "authenticated packaged smoke navigation escaped the allowlist"
        }
        require(source.assistantControllerReady) {
            "authenticated packaged smoke requires the assistant controller"
        }
        return PackagedAuthenticatedSmokeReport(
            profile = source.profile,
            oaLoopback = true,
            ready = true,
            identityEpoch = source.identityEpoch,
            workbenchReady = true,
            workbenchSections = source.workbenchSections.sorted(),
            navigationAllowlisted = true,
            assistantControllerReady = true,
        )
    }

    companion object {
        val REQUIRED_SECTIONS: Set<String> =
            setOf("notices", "shortcuts", "summary", "profile", "teams", "schedule")
        private val JSON = Json { encodeDefaults = true }
    }
}

@Serializable
private data class PackagedAuthenticatedSmokeReport(
    val profile: String,
    val oaLoopback: Boolean,
    val ready: Boolean,
    val identityEpoch: Long,
    val workbenchReady: Boolean,
    val workbenchSections: List<String>,
    val navigationAllowlisted: Boolean,
    val assistantControllerReady: Boolean,
)
