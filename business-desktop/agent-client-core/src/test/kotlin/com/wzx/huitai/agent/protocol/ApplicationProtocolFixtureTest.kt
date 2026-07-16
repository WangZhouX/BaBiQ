package com.wzx.huitai.agent.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ApplicationProtocolFixtureTest {
    @Test
    fun `canonical fixtures match all nineteen methods query responses and protocol error`() {
        val contractDirectory = repositoryRoot()
            .resolve("docs/superpowers/contracts/huitai-business-desktop-agent")
        val fixtures = fixtureCases()
        val missing = fixtures.map { it.fileName }
            .filterNot { Files.isRegularFile(contractDirectory.resolve(it)) }

        assertEquals(22, fixtures.size)
        assertEquals(22, fixtures.map { it.fileName }.toSet().size)
        assertTrue(
            missing.isEmpty(),
            "Missing canonical fixture files:\n${missing.joinToString("\n") { "- $it" }}",
        )

        fixtures.forEach { fixture ->
            val actual = ApplicationProtocol.JSON.parseToJsonElement(
                contractDirectory.resolve(fixture.fileName).readText(),
            )
            assertEquals(fixture.expected, actual, fixture.fileName)
        }
    }

    private fun fixtureCases(): List<FixtureCase> = listOf(
        requestFixture("catalog-register.json", "request-catalog-register", ApplicationMethod.CATALOG_REGISTER, catalogEnvelope(1, 3)),
        notificationFixture("catalog-update.json", ApplicationMethod.CATALOG_UPDATE, catalogEnvelope(2, 4)),
        notificationFixture("context-publish.json", ApplicationMethod.CONTEXT_PUBLISH, contextEnvelope(3, 9)),
        requestFixture("identity-bind.json", "request-identity-bind", ApplicationMethod.IDENTITY_BIND, identityEnvelope(4)),
        notificationFixture("identity-update.json", ApplicationMethod.IDENTITY_UPDATE, identityEnvelope(5)),
        requestFixture("action-request.json", "request-action", ApplicationMethod.ACTION_REQUEST, actionEnvelope(6, "requested")),
        notificationFixture("action-cancel.json", ApplicationMethod.ACTION_CANCEL, actionEnvelope(7, "canceled")),
        notificationFixture("action-accepted.json", ApplicationMethod.ACTION_ACCEPTED, actionEnvelope(8, "accepted")),
        notificationFixture("action-previewed.json", ApplicationMethod.ACTION_PREVIEWED, actionEnvelope(9, "previewed")),
        notificationFixture("action-approval-required.json", ApplicationMethod.ACTION_APPROVAL_REQUIRED, actionEnvelope(10, "waiting_approval")),
        notificationFixture("action-running.json", ApplicationMethod.ACTION_RUNNING, actionEnvelope(11, "executing")),
        notificationFixture("action-completed.json", ApplicationMethod.ACTION_COMPLETED, actionEnvelope(12, "succeeded")),
        notificationFixture("action-failed.json", ApplicationMethod.ACTION_FAILED, actionEnvelope(13, "failed")),
        notificationFixture("action-rejected.json", ApplicationMethod.ACTION_REJECTED, actionEnvelope(14, "rejected")),
        notificationFixture("action-canceled.json", ApplicationMethod.ACTION_CANCELED, actionEnvelope(15, "canceled")),
        notificationFixture("action-expired.json", ApplicationMethod.ACTION_EXPIRED, actionEnvelope(16, "expired")),
        notificationFixture("action-outcome-unknown.json", ApplicationMethod.ACTION_OUTCOME_UNKNOWN, actionEnvelope(17, "outcome_unknown")),
        requestFixture("action-status.json", "request-action-status", ApplicationMethod.ACTION_STATUS, actionEnvelope(18, "status_query")),
        requestFixture("action-result-get.json", "request-action-result", ApplicationMethod.ACTION_RESULT_GET, actionEnvelope(19, "result_query")),
        successFixture(
            "action-status-result.json",
            JsonRpcSuccessResponse(
                id = "request-action-status",
                result = buildJsonObject {
                    put("executionId", "execution-1")
                    put("state", "executing")
                },
            ),
        ),
        successFixture(
            "action-result-get-result.json",
            JsonRpcSuccessResponse(
                id = "request-action-result",
                result = buildJsonObject {
                    put("executionId", "execution-1")
                    put("state", "succeeded")
                    put("output", buildJsonObject { put("accepted", true) })
                },
            ),
        ),
        errorFixture(
            "protocol-error.json",
            JsonRpcErrorResponse(
                id = "request-action-status",
                error = JsonRpcError(
                    code = -32041,
                    message = "PROTOCOL_ERROR",
                    data = buildJsonObject { put("reason", "identity_scope_mismatch") },
                ),
            ),
        ),
    )

    private fun requestFixture(
        fileName: String,
        id: String,
        method: ApplicationMethod,
        params: ApplicationEnvelope,
    ) = fixture(
        fileName,
        JsonRpcRequest(id = id, method = method.wireName, params = params),
        JsonRpcRequest.serializer(),
    )

    private fun notificationFixture(
        fileName: String,
        method: ApplicationMethod,
        params: ApplicationEnvelope,
    ) = fixture(
        fileName,
        JsonRpcNotification(method = method.wireName, params = params),
        JsonRpcNotification.serializer(),
    )

    private fun successFixture(fileName: String, response: JsonRpcSuccessResponse) =
        fixture(fileName, response, JsonRpcSuccessResponse.serializer())

    private fun errorFixture(fileName: String, response: JsonRpcErrorResponse) =
        fixture(fileName, response, JsonRpcErrorResponse.serializer())

    private fun <T> fixture(fileName: String, value: T, serializer: KSerializer<T>) = FixtureCase(
        fileName = fileName,
        expected = ApplicationProtocol.JSON.encodeToJsonElement(serializer, value),
    )

    private fun catalogEnvelope(sequence: Long, catalogEpoch: Long) = CatalogEnvelope(
        common = commonFields(sequence),
        catalogEpoch = catalogEpoch,
        contextSequence = sequence,
        payloadSize = 48,
        payload = buildJsonObject {
            put("catalogRevision", "catalog-revision-1")
            put("actions", buildJsonObject {})
        },
    )

    private fun contextEnvelope(sequence: Long, contextSequence: Long) = ContextEnvelope(
        common = commonFields(sequence),
        catalogEpoch = 1,
        contextSequence = contextSequence,
        payloadSize = 42,
        payload = buildJsonObject {
            put("contextRevision", "context-revision-1")
            put("pageType", "framework-demo")
        },
    )

    private fun identityEnvelope(sequence: Long) = IdentityEnvelope(
        common = commonFields(sequence),
        authenticated = true,
        roles = setOf("lawyer"),
        permissions = setOf("framework:read"),
    )

    private fun actionEnvelope(sequence: Long, state: String) = ActionEnvelope(
        common = commonFields(sequence),
        threadId = "thread-1",
        turnId = "turn-1",
        toolCallId = "tool-call-1",
        executionId = "execution-1",
        payload = buildJsonObject {
            put("actionId", "framework.demo")
            put("state", state)
        },
    )

    private fun commonFields(sequence: Long) = CommonApplicationFields(
        protocolVersion = ApplicationProtocol.PROTOCOL_VERSION,
        desktopInstanceId = "desktop-1",
        desktopSessionId = "desktop-session-1",
        authSessionId = "auth-session-1",
        identityEpoch = 8,
        sequence = sequence,
        generatedAt = "2026-07-16T10:00:00Z",
        userId = "user-1",
        tenantId = "tenant-1",
        platformId = "platform-1",
    )

    private fun repositoryRoot(): Path = generateSequence(
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
    ) { it.parent }
        .firstOrNull {
            Files.isRegularFile(it.resolve("business-desktop/settings.gradle.kts")) &&
                Files.isDirectory(it.resolve("docs/superpowers"))
        }
        ?: error("Cannot locate BaBiQ repository root from ${System.getProperty("user.dir")}")

    private data class FixtureCase(
        val fileName: String,
        val expected: JsonElement,
    )
}
