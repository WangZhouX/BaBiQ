package com.wzx.huitai.desktop.integration

import com.wzx.huitai.agent.business.auth.BusinessAuthRpcClient
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchClient
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPage
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPageRequest
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchRpcClient
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSection
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSnapshot
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.JsonRpcSuccessResponse
import com.wzx.huitai.desktop.auth.BusinessAccessGateState
import com.wzx.huitai.desktop.auth.BusinessIdentityRegistry
import com.wzx.huitai.desktop.auth.BusinessRpcAuthenticationOperations
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchController
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchLoadState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Covers the local typed-RPC path from OA login projection to the first workbench page. */
class BusinessWorkbenchLifecycleIT {
    @Test
    fun `typed rpc login reaches ready workbench snapshot and first page`() = runTest {
        val backend = FakeBusinessBackendConnection()
        val rpc = AgentJsonRpcClient(backend, this)
        val authClient = BusinessAuthRpcClient(rpc)
        val registry = BusinessIdentityRegistry()
        val authentication = BusinessRpcAuthenticationOperations(
            client = authClient,
            identityRegistry = registry,
            desktopInstanceId = "desktop-workbench-it",
            desktopSessionId = "session-workbench-it",
            platformId = 2,
        )
        val workbenchClient = BusinessWorkbenchRpcClient(rpc)
        val workbench = BusinessWorkbenchController(workbenchClient)

        try {
            val candidate = authentication.findTenantCandidates("13800138000").single()
            val password = "Password8".toCharArray()
            authentication.authenticate("13800138000", password, candidate)

            assertEquals(BusinessAccessGateState.READY, registry.gate.value)
            assertEquals(7, registry.currentIdentity()?.identityEpoch)
            assertTrue(password.all { it == '\u0000' })

            workbench.load(identityEpoch = 7)
            workbench.loadPage()

            assertEquals(BusinessWorkbenchLoadState.READY, workbench.state.value.loadState)
            assertEquals(7, workbench.state.value.identityEpoch)
            assertEquals("welcome", workbench.state.value.snapshot?.notices?.data?.jsonObject?.get("id")?.jsonPrimitive?.content)
            assertEquals("case-1", workbench.state.value.page?.items?.single()?.id)
            assertEquals(
                setOf(
                    "business/auth/tenant-candidates",
                    "business/auth/login",
                    "business/workbench/get",
                    "business/workbench/navigation/get",
                    "business/workbench/page/get",
                ),
                backend.requests.mapNotNull { it["method"]?.jsonPrimitive?.content }.toSet(),
            )
            assertEquals(1, backend.requests.count { request ->
                request["method"]?.jsonPrimitive?.content == "business/auth/login"
            })
            assertEquals(1, backend.requests.count { it.toString().contains("Password8") })
            assertTrue(backend.requests.none { it.toString().contains("accessToken") })
        } finally {
            rpc.close()
        }
    }

    @Test
    fun `old workbench response is discarded after identity epoch changes`() = runTest {
        val client = EpochSwitchingWorkbenchClient()
        val controller = BusinessWorkbenchController(client)

        val oldLoad = async { controller.load(identityEpoch = 7) }
        client.oldRequestStarted.await()

        val freshLoad = async { controller.load(identityEpoch = 8) }
        client.freshRequestStarted.await()
        client.freshResponse.complete(Unit)
        freshLoad.await()

        assertEquals(8, controller.state.value.identityEpoch)
        assertEquals("fresh", controller.state.value.snapshot?.notices?.data?.jsonObject?.get("id")?.jsonPrimitive?.content)

        client.oldResponse.complete(Unit)
        oldLoad.await()

        assertEquals(8, controller.state.value.identityEpoch)
        assertEquals("fresh", controller.state.value.snapshot?.notices?.data?.jsonObject?.get("id")?.jsonPrimitive?.content)
    }

    private class FakeBusinessBackendConnection : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        val requests = CopyOnWriteArrayList<JsonObject>()

        override val connectionId: String = "workbench-it-connection"
        override val incoming: ReceiveChannel<String> = incomingChannel
        override val state = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true

        override suspend fun send(text: String) {
            val request = ApplicationProtocol.JSON.parseToJsonElement(text).jsonObject
            requests += request
            val id = request.getValue("id").jsonPrimitive.long
            val result = when (request.getValue("method").jsonPrimitive.content) {
                "business/auth/tenant-candidates" -> buildJsonObject {
                    put("candidates", buildJsonArray {
                        add(buildJsonObject {
                            put("candidateId", "candidate-1")
                            put("name", "Tenant")
                            put("status", "AVAILABLE")
                            put("platformId", 2)
                            put("tenantEnterStatus", 0)
                        })
                    })
                }
                "business/auth/login" -> readySession()
                "business/workbench/get" -> buildJsonObject {
                    put("identityEpoch", 7)
                    put("generation", 2)
                    put("snapshot", buildJsonObject {
                        put("notices", section("welcome"))
                        put("shortcuts", section("shortcuts"))
                        put("summary", section("summary"))
                    })
                }
                "business/workbench/navigation/get" -> buildJsonObject {
                    put("identityEpoch", 7)
                    put("generation", 2)
                    put("items", buildJsonArray {
                        add(buildJsonObject { put("kind", "WORKBENCH"); put("path", "/"); put("title", "Workbench") })
                    })
                }
                "business/workbench/page/get" -> buildJsonObject {
                    put("identityEpoch", 7)
                    put("generation", 2)
                    put("total", 1)
                    put("pageNo", 1)
                    put("pageSize", 20)
                    put("items", buildJsonArray {
                        add(buildJsonObject { put("id", "case-1"); put("title", "Case") })
                    })
                }
                else -> error("Unexpected method: ${request.getValue("method")}")
            }
            incomingChannel.send(
                ApplicationProtocol.JSON.encodeToString(
                    JsonRpcSuccessResponse.serializer(),
                    JsonRpcSuccessResponse(id = id, result = result),
                ),
            )
        }

        override suspend fun close() {
            incomingChannel.close()
        }

        private fun readySession() = buildJsonObject {
            put("status", "READY")
            put("authSessionId", "auth-session-7")
            put("identityEpoch", 7)
            put("generation", 2)
            put("platformId", "2")
            put("user", buildJsonObject { put("id", "user-1"); put("name", "Lawyer") })
            put("tenant", buildJsonObject { put("id", "tenant-1"); put("name", "Tenant") })
            put("roles", JsonArray(emptyList()))
            put("permissions", JsonArray(emptyList()))
        }

        private fun section(id: String) = buildJsonObject {
            put("status", "OK")
            put("data", buildJsonObject { put("id", id) })
        }
    }

    private class EpochSwitchingWorkbenchClient : BusinessWorkbenchClient {
        val oldRequestStarted = CompletableDeferred<Unit>()
        val freshRequestStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<Unit>()
        val freshResponse = CompletableDeferred<Unit>()
        private val getCalls = AtomicInteger()

        override suspend fun get(month: String?, day: String?): BusinessWorkbenchSnapshot {
            return if (getCalls.getAndIncrement() == 0) {
                oldRequestStarted.complete(Unit)
                oldResponse.await()
                snapshot(7, "old")
            } else {
                freshRequestStarted.complete(Unit)
                freshResponse.await()
                snapshot(8, "fresh")
            }
        }

        override suspend fun navigation() =
            com.wzx.huitai.agent.business.workbench.BusinessWorkbenchNavigation(
                identityEpoch = 8,
                generation = 1,
                items = emptyList(),
            )

        override suspend fun homeInfo() = BusinessWorkbenchSection()

        override suspend fun teamRoles(
            teamId: String,
            kind: com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind,
        ) = com.wzx.huitai.agent.business.workbench.BusinessWorkbenchTeamRoles(8, 1, emptyList())

        override suspend fun updateSort(
            request: com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortRequest,
        ) = com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortMutation(
            8,
            1,
            request.expectedRevision + 1,
            false,
            request.ids,
        )

        override suspend fun page(request: BusinessWorkbenchPageRequest) =
            BusinessWorkbenchPage(8, 1, 0, request.pageNo, request.pageSize, emptyList())

        private fun snapshot(epoch: Long, id: String) = BusinessWorkbenchSnapshot(
            identityEpoch = epoch,
            generation = 1,
            notices = BusinessWorkbenchSection(
                data = buildJsonObject { put("id", id) },
            ),
        )
    }
}
