package com.wzx.huitai.desktop.security

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source contract for the local-gateway boundary.
 *
 * Compose may use the local Agent WebSocket and its desktop-session Bearer,
 * but it must not retain a remote OA URL, OA token, OA HTTP client, or OA
 * WebSocket gateway. This test intentionally scans production sources rather
 * than asserting a particular composition implementation.
 */
class ComposeOaDirectAccessForbiddenTest {
    @Test
    fun `production sources do not retain OA token fields`() {
        val root = businessDesktopRoot()
        val violations = allProductionSources(root).mapNotNull { source ->
            val rawText = runCatching { Files.readString(source) }.getOrNull() ?: return@mapNotNull null
            val text = stripCommentsPreservingStrings(rawText)
            val match = OA_TOKEN_FIELD_MARKER.find(text) ?: return@mapNotNull null
            val relative = root.relativize(source).toString().replace('\\', '/')
            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
            "$relative:$line OA token field"
        }

        assertTrue(
            violations.isEmpty(),
            "Compose production sources must not retain OA access/refresh tokens:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `production Authorization usage is limited to the local desktop session transport`() {
        val root = businessDesktopRoot()
        val violations = allProductionSources(root).flatMap { source ->
            val rawText = runCatching { Files.readString(source) }.getOrNull() ?: return@flatMap emptyList()
            val text = stripCommentsPreservingStrings(rawText)
            val relative = root.relativize(source).toString().replace('\\', '/')
            AUTHORIZATION_SEND_MARKER.findAll(text).flatMap { match ->
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                if (isApprovedLocalAuthorizationTransport(root, relative, text, match.range)) {
                    emptySequence()
                } else {
                    sequenceOf("$relative:$line Authorization header send")
                }
            }.toList()
        }

        assertTrue(
            violations.isEmpty(),
            "Compose production sources must not send remote Authorization headers:\n" +
                violations.joinToString("\n"),
        )

        val localTransport = Files.readString(root.resolve(LOCAL_AGENT_TRANSPORT))
        assertTrue(localTransport.contains("request.identity.desktopSessionToken"))
        assertTrue(localTransport.contains("request.url"))
        assertTrue(localTransport.contains("X-Desktop-Instance-Id"))
        assertTrue(localTransport.contains("X-Desktop-Session-Id"))
        assertTrue(!localTransport.contains("accessToken"))
        assertTrue(!localTransport.contains("refreshToken"))
        assertTrue(!localTransport.contains("tenant-id"))

        val requestContract = Files.readString(root.resolve(LOCAL_AGENT_REQUEST_CONTRACT))
        assertTrue(requestContract.contains("validateLoopbackWebSocketUrl(url)"))
        assertTrue(requestContract.contains("host == \"localhost\""))
        assertTrue(requestContract.contains("isIpv4Loopback(host)"))
        assertTrue(requestContract.contains("uri.userInfo == null"))

        val attachmentTransport = Files.readString(root.resolve(LOCAL_ATTACHMENT_TRANSPORT))
        assertTrue(attachmentTransport.contains("uri.host == \"127.0.0.1\" || uri.host == \"::1\""))
        assertTrue(attachmentTransport.contains("identity.localOrigin =="))
        assertTrue(attachmentTransport.contains("\"Authorization\" to \"Bearer \${identity.desktopSessionToken}\""))
        assertTrue(!attachmentTransport.contains("accessToken"))
        assertTrue(!attachmentTransport.contains("refreshToken"))
        assertTrue(!attachmentTransport.contains("tenant-id"))
    }

    @Test
    fun `a second Authorization send in an approved transport is rejected occurrence by occurrence`() {
        val root = businessDesktopRoot()
        val approvedSource = Files.readString(root.resolve(LOCAL_AGENT_TRANSPORT))
        val injectedRemoteSend = approvedSource + """

            suspend fun forbiddenRemoteCall() {
                httpClient.prepareRequest("https://oa.example.invalid") {
                    header(HttpHeaders.Authorization, "Bearer ${'$'}remoteOaToken")
                }
            }
        """.trimIndent()
        val occurrences = AUTHORIZATION_SEND_MARKER.findAll(injectedRemoteSend).toList()

        assertEquals(2, occurrences.size)
        assertTrue(
            isApprovedLocalAuthorizationTransport(
                root,
                LOCAL_AGENT_TRANSPORT,
                injectedRemoteSend,
                occurrences.first().range,
            ),
        )
        assertTrue(
            !isApprovedLocalAuthorizationTransport(
                root,
                LOCAL_AGENT_TRANSPORT,
                injectedRemoteSend,
                occurrences.last().range,
            ),
        )
    }

    @Test
    fun `production sources do not retain remote OA direct access`() {
        val root = businessDesktopRoot()
        val violations = allProductionSources(root).flatMap { source ->
            val rawText = runCatching { Files.readString(source) }.getOrNull() ?: return@flatMap emptyList()
            val text = stripCommentsPreservingStrings(rawText)
            val relative = root.relativize(source).toString().replace('\\', '/')
            val markers = FORBIDDEN_MARKERS + if (relative.startsWith("app/src/main/")) APP_FORBIDDEN_MARKERS else emptyList()
            val sourceViolations = markers.mapNotNull { marker ->
                marker.pattern.find(text)?.let { match ->
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    "$relative:$line ${marker.name}"
                }
            }.toMutableList()
            sourceViolations
        }

        assertTrue(
            violations.isEmpty(),
            "Compose production sources must use the local Spring Boot gateway only; " +
                "forbidden OA direct-access markers:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `only the three migration aliases retain legacy OA credential names`() {
        val root = businessDesktopRoot()
        val occurrences = allProductionSources(root).flatMap { source ->
            val text = runCatching { Files.readString(source) }.getOrNull() ?: return@flatMap emptyList()
            val relative = root.relativize(source).toString().replace('\\', '/')
            LEGACY_ALIAS_MARKER.findAll(text).map { relative to it.value }.toList()
        }

        assertTrue(occurrences.all { it.first == LEGACY_ALIAS_CLEANUP })
        assertEquals(
            LEGACY_OA_ALIASES,
            occurrences.mapTo(linkedSetOf()) { it.second },
        )
        assertEquals(LEGACY_OA_ALIASES.size, occurrences.size)
    }

    private fun isApprovedLocalAuthorizationTransport(
        root: Path,
        relative: String,
        source: String,
        occurrence: IntRange,
    ): Boolean = when (relative) {
        LOCAL_AGENT_TRANSPORT -> {
            val requestContract = Files.readString(root.resolve(LOCAL_AGENT_REQUEST_CONTRACT))
            AGENT_AUTHORIZATION_SITE.findAll(source).count {
                occurrence.first >= it.range.first && occurrence.last <= it.range.last
            } == 1 &&
                requestContract.contains("validateLoopbackWebSocketUrl(url)") &&
                requestContract.contains("host == \"localhost\"") &&
                requestContract.contains("isIpv4Loopback(host)") &&
                requestContract.contains("uri.userInfo == null")
        }
        LOCAL_ATTACHMENT_TRANSPORT ->
            ATTACHMENT_AUTHORIZATION_SITE.findAll(source).count {
                occurrence.first >= it.range.first && occurrence.last <= it.range.last
            } == 1
        else -> false
    }

    private fun productionSources(root: Path): List<Path> =
        listOf(root.resolve("app/src/main"), root.resolve("agent-client-core/src/main"))
            .flatMap(::textSources)

    private fun allProductionSources(root: Path): List<Path> =
        Files.list(root).use { stream ->
            stream
                .filter(Files::isDirectory)
                .map { module -> module.resolve("src/main") }
                .filter(Files::isDirectory)
                .flatMap { sourceRoot -> textSources(sourceRoot).stream() }
                .collect(Collectors.toList())
        }

    private fun textSources(sourceRoot: Path): List<Path> =
        Files.walk(sourceRoot).use { stream ->
            stream
                .filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().substringAfterLast('.', "") in TEXT_EXTENSIONS
                }
                .collect(Collectors.toList())
        }

    private fun stripCommentsPreservingStrings(source: String): String {
        val output = StringBuilder(source.length)
        var index = 0
        var quote: Char? = null
        var escaped = false
        while (index < source.length) {
            val current = source[index]
            if (quote != null) {
                output.append(current)
                if (escaped) escaped = false
                else if (current == '\\') escaped = true
                else if (current == quote) quote = null
                index += 1
                continue
            }
            if (current == '"' || current == '\'') {
                quote = current
                output.append(current)
                index += 1
                continue
            }
            if (current == '/' && index + 1 < source.length && source[index + 1] == '/') {
                index += 2
                while (index < source.length && source[index] != '\n') index += 1
                continue
            }
            if (current == '/' && index + 1 < source.length && source[index + 1] == '*') {
                index += 2
                while (index + 1 < source.length && !(source[index] == '*' && source[index + 1] == '/')) {
                    if (source[index] == '\n') output.append('\n')
                    index += 1
                }
                index = (index + 2).coerceAtMost(source.length)
                continue
            }
            output.append(current)
            index += 1
        }
        return output.toString()
    }

    private fun businessDesktopRoot(): Path {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        val candidates = buildList {
            var current: Path? = workingDirectory
            while (current != null) {
                add(current)
                current = current.parent
            }
            add(workingDirectory.resolve("business-desktop"))
        }
        return candidates.firstOrNull { candidate ->
            Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
                Files.isDirectory(candidate.resolve("app/src/main"))
        } ?: error("Unable to locate business-desktop project root from $workingDirectory")
    }

    private data class ForbiddenMarker(val name: String, val pattern: Regex)

    private companion object {
        val TEXT_EXTENSIONS = setOf("kt", "java", "properties", "yml", "yaml", "json", "xml", "txt", "conf")

        val FORBIDDEN_MARKERS = listOf(
            ForbiddenMarker("OA URL/configuration", Regex("(?i)huitai\\.oa\\.|cloud\\.huitaikeji\\.cn|192\\.168\\.1\\.20:48080")),
            ForbiddenMarker("OA authentication gateway", Regex("(?i)\\b(?:OaAuthenticationGateway(?:Factory|Bundle)?|KtorOaAuthenticationGateway)\\b")),
            ForbiddenMarker("OA HTTP client", Regex("(?i)\\bHuitaiHttpClient\\b")),
            ForbiddenMarker("OA WebSocket client", Regex("(?i)\\b(?:KtorHuitaiWebSocketTransport|HuitaiWebSocketTransport|HuitaiWebSocketConnectRequest)\\b")),
            ForbiddenMarker("remote tenant header", Regex("(?i)[\"']tenant-id[\"']")),
        )

        val APP_FORBIDDEN_MARKERS = listOf(
            ForbiddenMarker("OA authentication model", Regex("(?i)\\b(?:OaPreAuthenticationGateway|OaCandidateAuthenticationGateway|OaCandidateAccess|OaPermissionInfo|OaTokenBundle|OaTenantCandidate)\\b")),
            ForbiddenMarker("legacy authenticated HTTP client", Regex("(?i)\\bReadyAuthenticated(?:HttpGate|HuitaiClient)\\b")),
            ForbiddenMarker("legacy OA refresh adapter", Regex("(?i)\\bOaTokenRefreshAdapter\\b")),
            ForbiddenMarker("legacy authentication orchestrator", Regex("(?i)\\bBusinessAuthenticationOrchestrator\\b")),
            ForbiddenMarker("legacy token refresh coordinator", Regex("(?i)\\bTokenRefreshCoordinator\\b")),
            ForbiddenMarker("legacy OA session manager", Regex("(?i)\\bAuthSessionManager\\b")),
            ForbiddenMarker("legacy desktop OA credential persistence", Regex("(?i)\\b(?:JceksAuthCredentialPersistence|BusinessAuthSessionMetadataStore|BusinessAuthRevocationMarkerStore)\\b")),
            ForbiddenMarker("legacy identity binding adapter", Regex("(?i)\\b(?:ApplicationIdentityBindingAdapter|ProductionIdentityBoundaryActionAdapter)\\b")),
            ForbiddenMarker("OA configuration type", Regex("(?i)\\bBusinessOaConfiguration(?:Loader|Bootstrap)?\\b")),
        )

        val AUTHORIZATION_SEND_MARKER = Regex(
            "(?is)(?:HttpHeaders\\.Authorization|header\\s*\\([^)]*Authorization|[\"']Authorization[\"']\\s+to)",
        )
        val AGENT_AUTHORIZATION_SITE = Regex(
            """(?s)httpClient\.prepareRequest\(request\.url\)\s*\{\s*""" +
                """header\(HttpHeaders\.Authorization,\s*"Bearer \$\{request\.identity\.desktopSessionToken\}"\)\s*""" +
                """header\("X-Desktop-Instance-Id",\s*request\.identity\.desktopInstanceId\)\s*""" +
                """header\("X-Desktop-Session-Id",\s*request\.identity\.desktopSessionId\)\s*""" +
                """header\(HttpHeaders\.Origin,\s*request\.identity\.localOrigin\)\s*\}\.execute""",
        )
        val ATTACHMENT_AUTHORIZATION_SITE = Regex(
            """(?s)BusinessAttachmentHttpRequest\(\s*""" +
                """url\s*=\s*endpoint\.baseUrl\.trimEnd\('/'\)\s*\+\s*""" +
                """"/business/attachments/uploads/\$\{prepared\.attachmentBatchId\}",\s*""" +
                """headers\s*=\s*mapOf\(\s*""" +
                """"X-Business-Upload-Ticket"\s+to\s+prepared\.ticket,\s*""" +
                """"Authorization"\s+to\s+"Bearer \$\{identity\.desktopSessionToken\}",\s*""" +
                """"Origin"\s+to\s+identity\.localOrigin,\s*""" +
                """"X-Desktop-Instance-Id"\s+to\s+identity\.desktopInstanceId,\s*""" +
                """"X-Desktop-Session-Id"\s+to\s+identity\.desktopSessionId,\s*""" +
                """\),\s*paths\s*=\s*paths\.toList\(\),\s*\)""",
        )
        val OA_TOKEN_FIELD_MARKER = Regex(
            "(?m)\\b(?:val|var)\\s+(?:accessToken|refreshToken|oaToken)\\s*:\\s*(?:String|CharArray)\\??|" +
                "\\b(?:accessToken|refreshToken|oaToken)\\s*\\(\\s*\\)\\s*:\\s*String\\??",
        )
        const val LOCAL_AGENT_TRANSPORT =
            "agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/KtorAgentTransport.kt"
        const val LOCAL_ATTACHMENT_TRANSPORT =
            "app/src/main/kotlin/com/wzx/huitai/desktop/workbench/BusinessAttachmentUploadClient.kt"
        const val LOCAL_AGENT_REQUEST_CONTRACT =
            "agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/AgentTransport.kt"
        const val LEGACY_ALIAS_CLEANUP =
            "app/src/main/kotlin/com/wzx/huitai/desktop/security/LegacyOaCredentialAliasCleanup.kt"
        val LEGACY_ALIAS_MARKER = Regex(
            "[\"'](?:huitai\\.auth\\.[^\"']+|huitai\\.login\\.remembered\\.[^\"']+)[\"']",
        )
        val LEGACY_OA_ALIASES = setOf(
            "\"huitai.auth.tokens.v1\"",
            "\"huitai.auth.session-metadata.v1\"",
            "\"huitai.login.remembered.v1\"",
        )
    }
}
