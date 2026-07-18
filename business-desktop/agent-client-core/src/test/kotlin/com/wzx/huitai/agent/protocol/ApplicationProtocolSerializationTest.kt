package com.wzx.huitai.agent.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationProtocolSerializationTest {
    @Test
    fun `all nineteen methods round trip with their method specific envelope type`() {
        val requestMethods = setOf(
            ApplicationMethod.CATALOG_REGISTER,
            ApplicationMethod.IDENTITY_BIND,
            ApplicationMethod.ACTION_REQUEST,
            ApplicationMethod.ACTION_STATUS,
            ApplicationMethod.ACTION_RESULT_GET,
        )
        val cases = ApplicationMethod.entries.mapIndexed { index, method ->
            val params = envelopeFor(method, sequence = 100L + index)
            val decodedMethod: String
            val decodedParams: ApplicationEnvelope
            if (method in requestMethods) {
                val original = JsonRpcRequest(
                    id = index.toLong() + 1,
                    method = method.wireName,
                    params = params,
                )
                val decoded = roundTrip(original, JsonRpcRequest.serializer())
                decodedMethod = decoded.method
                decodedParams = decoded.params
            } else {
                val original = JsonRpcNotification(
                    method = method.wireName,
                    params = params,
                )
                val decoded = roundTrip(original, JsonRpcNotification.serializer())
                decodedMethod = decoded.method
                decodedParams = decoded.params
            }

            assertEquals(method.wireName, decodedMethod)
            assertEquals(params::class, decodedParams::class, message = method.wireName)
            assertEquals(params, decodedParams, message = method.wireName)
            method
        }

        assertEquals(19, cases.size)
        assertEquals(ApplicationMethod.entries.toSet(), cases.toSet())
    }

    @Test
    fun `json rpc method disambiguates catalog and context envelopes`() {
        val catalog = CatalogEnvelope(
            common = commonFields(sequence = 20),
            catalogEpoch = 6,
            contextSequence = 12,
            payloadSize = 2,
            payload = buildJsonObject { put("kind", "catalog") },
        )
        val context = ContextEnvelope(
            common = commonFields(sequence = 21),
            catalogEpoch = 6,
            contextSequence = 13,
            payloadSize = 3,
            payload = buildJsonObject { put("kind", "context") },
        )
        val catalogRequest = JsonRpcRequest(
            id = 20,
            method = ApplicationMethod.CATALOG_REGISTER.wireName,
            params = catalog,
        )
        val contextNotification = JsonRpcNotification(
            method = ApplicationMethod.CONTEXT_PUBLISH.wireName,
            params = context,
        )

        val decodedCatalog = roundTrip(catalogRequest, JsonRpcRequest.serializer())
        val decodedContext = roundTrip(contextNotification, JsonRpcNotification.serializer())

        val decodedCatalogParams = decodedCatalog.params
        val decodedContextParams = decodedContext.params
        assertTrue(decodedCatalogParams is CatalogEnvelope)
        assertTrue(decodedContextParams is ContextEnvelope)
        assertEquals(6, decodedCatalogParams.catalogEpoch)
        assertEquals(12, decodedCatalogParams.contextSequence)
        assertEquals(2, decodedCatalogParams.payloadSize)
        assertEquals(6, decodedContextParams.catalogEpoch)
        assertEquals(13, decodedContextParams.contextSequence)
        assertEquals(3, decodedContextParams.payloadSize)
    }

    @Test
    fun `catalog and context ordering fields are mandatory constructor dependencies`() {
        listOf(CatalogEnvelope::class.java, ContextEnvelope::class.java).forEach { envelopeClass ->
            val hasDefaultArgumentConstructor = envelopeClass.declaredConstructors.any { constructor ->
                constructor.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker"
            }
            assertFalse(hasDefaultArgumentConstructor, message = envelopeClass.simpleName)
        }
    }

    @Test
    fun `application protocol exposes exactly nineteen method names`() {
        assertEquals(
            setOf(
                "application/catalog/register",
                "application/catalog/update",
                "application/context/publish",
                "application/identity/bind",
                "application/identity/update",
                "application/action/request",
                "application/action/cancel",
                "application/action/accepted",
                "application/action/previewed",
                "application/action/approval-required",
                "application/action/running",
                "application/action/completed",
                "application/action/failed",
                "application/action/rejected",
                "application/action/canceled",
                "application/action/expired",
                "application/action/outcome-unknown",
                "application/action/status",
                "application/action/result/get",
            ),
            ApplicationMethod.entries.map { it.wireName }.toSet(),
        )
        assertEquals(19, ApplicationMethod.entries.size)
    }

    @Test
    fun `request notification and responses round trip`() {
        val request = JsonRpcRequest(
            id = 1,
            method = ApplicationMethod.ACTION_STATUS.wireName,
            params = commonActionEnvelope(sequence = 1),
        )
        val notification = JsonRpcNotification(
            method = ApplicationMethod.ACTION_RUNNING.wireName,
            params = commonActionEnvelope(sequence = 2),
        )
        val success = JsonRpcSuccessResponse(
            id = 1,
            result = buildJsonObject {
                put("executionId", "execution-1")
                put("state", "running")
            },
        )
        val error = JsonRpcErrorResponse(
            id = 2,
            error = JsonRpcError(
                code = -32041,
                message = "PROTOCOL_ERROR",
                data = buildJsonObject { put("reason", "identity_scope_mismatch") },
            ),
        )

        assertEquals(request, roundTrip(request, JsonRpcRequest.serializer()))
        assertEquals(notification, roundTrip(notification, JsonRpcNotification.serializer()))
        assertEquals(success, roundTrip(success, JsonRpcSuccessResponse.serializer()))
        assertEquals(error, roundTrip(error, JsonRpcErrorResponse.serializer()))
    }

    @Test
    fun `all application envelopes contain common identity and ordering fields`() {
        val envelopes: List<ApplicationEnvelope> = listOf(
            CatalogEnvelope(
                common = commonFields(sequence = 1),
                catalogEpoch = 3,
                contextSequence = 8,
                payloadSize = 7,
                payload = buildJsonObject { put("actions", 7) },
            ),
            ContextEnvelope(
                common = commonFields(sequence = 2),
                catalogEpoch = 3,
                contextSequence = 9,
                payloadSize = 4,
                payload = buildJsonObject { put("pageId", "case-list") },
            ),
            ActionEnvelope(
                common = commonFields(sequence = 3),
                threadId = "thread-1",
                turnId = "turn-1",
                toolCallId = "tool-call-1",
                executionId = "execution-1",
                payload = buildJsonObject { put("state", "accepted") },
            ),
            IdentityEnvelope(
                common = commonFields(sequence = 4),
                authenticated = true,
                roles = setOf("lawyer"),
                permissions = setOf("case:read"),
            ),
        )

        envelopes.forEach { envelope ->
            val encoded = ApplicationProtocol.JSON.encodeToJsonElement(
                ApplicationEnvelope.serializer(),
                envelope,
            ).jsonObject
            assertEquals(ApplicationProtocol.PROTOCOL_VERSION, encoded.string("protocolVersion"))
            assertEquals("desktop-1", encoded.string("desktopInstanceId"))
            assertEquals("desktop-session-1", encoded.string("desktopSessionId"))
            assertEquals("auth-session-1", encoded.string("authSessionId"))
            assertEquals(8L, encoded.long("identityEpoch"))
            assertTrue(encoded.long("sequence") > 0)
            assertEquals("2026-07-16T10:00:00Z", encoded.string("generatedAt"))
            assertEquals("user-1", encoded.string("userId"))
            assertEquals("tenant-1", encoded.string("tenantId"))
            assertEquals("platform-1", encoded.string("platformId"))
        }
    }

    @Test
    fun `catalog context and action envelopes preserve their required metadata`() {
        val catalog = roundTrip(
            CatalogEnvelope(
                common = commonFields(1),
                catalogEpoch = 3,
                contextSequence = 10,
                payloadSize = 2,
                payload = buildJsonObject { put("kind", "catalog") },
            ),
            CatalogEnvelope.serializer(),
        )
        val context = roundTrip(
            ContextEnvelope(
                common = commonFields(2),
                catalogEpoch = 4,
                contextSequence = 11,
                payloadSize = 5,
                payload = buildJsonObject { put("kind", "context") },
            ),
            ContextEnvelope.serializer(),
        )
        val action = roundTrip(commonActionEnvelope(3), ActionEnvelope.serializer())

        assertEquals(3, catalog.catalogEpoch)
        assertEquals(10, catalog.contextSequence)
        assertEquals(2, catalog.payloadSize)
        assertEquals(4, context.catalogEpoch)
        assertEquals(11, context.contextSequence)
        assertEquals(5, context.payloadSize)

        val catalogJson = ApplicationProtocol.JSON.encodeToJsonElement(CatalogEnvelope.serializer(), catalog).jsonObject
        val contextJson = ApplicationProtocol.JSON.encodeToJsonElement(ContextEnvelope.serializer(), context).jsonObject
        assertEquals(3L, catalogJson.long("catalogEpoch"))
        assertEquals(10L, catalogJson.long("contextSequence"))
        assertEquals(2L, catalogJson.long("payloadSize"))
        assertEquals(4L, contextJson.long("catalogEpoch"))
        assertEquals(11L, contextJson.long("contextSequence"))
        assertEquals(5L, contextJson.long("payloadSize"))
        assertEquals("thread-1", action.threadId)
        assertEquals("turn-1", action.turnId)
        assertEquals("tool-call-1", action.toolCallId)
        assertEquals("execution-1", action.executionId)
    }

    @Test
    fun `signed out identity update omits business identity fields`() {
        val signedOut = IdentityEnvelope(
            common = CommonApplicationFields(
                protocolVersion = ApplicationProtocol.PROTOCOL_VERSION,
                desktopInstanceId = "desktop-1",
                desktopSessionId = "desktop-session-1",
                authSessionId = null,
                identityEpoch = 9,
                sequence = 5,
                generatedAt = "2026-07-16T10:01:00Z",
                userId = null,
                tenantId = null,
                platformId = null,
            ),
            authenticated = false,
            roles = emptySet(),
            permissions = emptySet(),
        )
        val encoded = ApplicationProtocol.JSON.encodeToJsonElement(
            IdentityEnvelope.serializer(),
            signedOut,
        ).jsonObject

        assertFalse(encoded["authenticated"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, encoded["authSessionId"])
        assertEquals(JsonNull, encoded["userId"])
        assertEquals(JsonNull, encoded["tenantId"])
        assertEquals(JsonNull, encoded["platformId"])
        assertEquals(signedOut, ApplicationProtocol.JSON.decodeFromJsonElement(IdentityEnvelope.serializer(), encoded))
    }

    @Test
    fun `wire enums use lower snake case and unknown fields are ignored`() {
        assertEquals("waiting_approval", ApplicationProtocol.JSON.encodeToString(ApplicationActionState.serializer(), ApplicationActionState.WAITING_APPROVAL).trim('"'))
        assertEquals("outcome_unknown", ApplicationProtocol.JSON.encodeToString(ApplicationActionState.serializer(), ApplicationActionState.OUTCOME_UNKNOWN).trim('"'))

        val jsonWithFutureField = """
            {
              "jsonrpc":"2.0",
              "id":3,
              "result":{"executionId":"execution-1"},
              "futureServerField":{"enabled":true}
            }
        """.trimIndent()
        val decoded = ApplicationProtocol.JSON.decodeFromString(JsonRpcSuccessResponse.serializer(), jsonWithFutureField)

        assertEquals(3L, decoded.id)
        assertEquals("execution-1", decoded.result.string("executionId"))
    }

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

    private fun commonActionEnvelope(sequence: Long) = ActionEnvelope(
        common = commonFields(sequence),
        threadId = "thread-1",
        turnId = "turn-1",
        toolCallId = "tool-call-1",
        executionId = "execution-1",
        payload = buildJsonObject { put("state", "running") },
    )

    private fun envelopeFor(method: ApplicationMethod, sequence: Long): ApplicationEnvelope = when (method) {
        ApplicationMethod.CATALOG_REGISTER,
        ApplicationMethod.CATALOG_UPDATE,
        -> CatalogEnvelope(
            common = commonFields(sequence),
            catalogEpoch = 3,
            contextSequence = 9,
            payloadSize = 1,
            payload = buildJsonObject { put("kind", "catalog") },
        )

        ApplicationMethod.CONTEXT_PUBLISH -> ContextEnvelope(
            common = commonFields(sequence),
            catalogEpoch = 3,
            contextSequence = 9,
            payloadSize = 1,
            payload = buildJsonObject { put("kind", "context") },
        )

        ApplicationMethod.IDENTITY_BIND,
        ApplicationMethod.IDENTITY_UPDATE,
        -> IdentityEnvelope(
            common = commonFields(sequence),
            authenticated = true,
            roles = setOf("lawyer"),
            permissions = setOf("case:read"),
        )

        else -> commonActionEnvelope(sequence)
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.content.toLong()

    private fun <T> roundTrip(value: T, serializer: kotlinx.serialization.KSerializer<T>): T =
        ApplicationProtocol.JSON.decodeFromString(
            serializer,
            ApplicationProtocol.JSON.encodeToString(serializer, value),
        )
}
