package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchClient
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchNavigation
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPage
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPageRequest
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSection
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSectionStatus
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSnapshot
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortMutation
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortRequest
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchTeamRole
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchTeamRoles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BusinessWorkbenchInteractionTest {
    @Test
    fun `loaded snapshot maps first enabled summary and drops invalid team and role selections`() {
        val reducer = BusinessWorkbenchReducer()
        var state = BusinessWorkbenchState(
            identityEpoch = 7,
            selectedKind = BusinessWorkbenchKind.CASE,
            scope = BusinessWorkbenchScope.TEAM,
            teamId = "team-no-longer-authorized",
            roleCode = "ROLE_NO_LONGER_AUTHORIZED",
            pageNo = 4,
        )
        state = reducer.reduce(state, BusinessWorkbenchEvent.LoadStarted(1, 7))
        state = reducer.reduce(
            state,
            BusinessWorkbenchEvent.Loaded(
                1,
                BusinessWorkbenchSnapshot(
                    identityEpoch = 7,
                    generation = 9,
                    summary = BusinessWorkbenchSection(
                        BusinessWorkbenchSectionStatus.OK,
                        buildJsonArray {
                            add(buildJsonObject { put("configCode", "case_handle"); put("enabled", false) })
                            add(buildJsonObject { put("configCode", "appointment"); put("enabled", true) })
                        },
                    ),
                    teams = BusinessWorkbenchSection(
                        BusinessWorkbenchSectionStatus.OK,
                        buildJsonArray { add(buildJsonObject { put("id", "team-authorized") }) },
                    ),
                ),
                emptyList(),
            ),
        )

        assertEquals(BusinessWorkbenchKind.APPOINTMENT, state.selectedKind)
        assertEquals(1, state.selectedStatistic)
        assertEquals(BusinessWorkbenchScope.ALL, state.scope)
        assertNull(state.teamId)
        assertNull(state.roleCode)
        assertEquals(1, state.pageNo)
    }

    @Test
    fun `team scope selects an authorized team loads roles then loads page`() = runTest {
        val client = InteractionClient()
        val controller = BusinessWorkbenchController(client)
        controller.load(7)

        controller.changeScope(BusinessWorkbenchScope.TEAM)

        assertEquals(BusinessWorkbenchScope.TEAM, controller.state.value.scope)
        assertEquals("team-1", controller.state.value.teamId)
        assertEquals(listOf("OWNER"), controller.state.value.roles.map { it.roleCode })
        assertEquals("team-1", client.roleRequests.single().first)
        assertEquals(BusinessWorkbenchScope.TEAM, client.pageRequests.single().scope)
    }

    @Test
    fun `statistic selection maps kind resets page and loads it`() = runTest {
        val client = InteractionClient()
        val controller = BusinessWorkbenchController(client)
        controller.load(7)

        controller.changeStatistic(1)

        assertEquals(BusinessWorkbenchKind.APPOINTMENT, controller.state.value.selectedKind)
        assertEquals(1, controller.state.value.pageNo)
        assertEquals(BusinessWorkbenchKind.APPOINTMENT, client.pageRequests.single().kind)
    }

    @Test
    fun `all four statistics map to their typed list and reset page`() = runTest {
        val client = InteractionClient()
        val controller = BusinessWorkbenchController(client)
        controller.load(7)

        listOf(
            BusinessWorkbenchKind.CASE,
            BusinessWorkbenchKind.APPOINTMENT,
            BusinessWorkbenchKind.COUNSELOR_SERVICE,
            BusinessWorkbenchKind.VISIT,
        ).forEachIndexed { index, expected ->
            controller.changeStatistic(index)
            assertEquals(expected, controller.state.value.selectedKind)
            assertEquals(1, controller.state.value.pageNo)
        }

        assertEquals(
            listOf(
                BusinessWorkbenchKind.CASE,
                BusinessWorkbenchKind.APPOINTMENT,
                BusinessWorkbenchKind.COUNSELOR_SERVICE,
                BusinessWorkbenchKind.VISIT,
            ),
            client.pageRequests.map { it.kind },
        )
    }

    @Test
    fun `sort failure rolls optimistic order back and success adopts canonical revision`() = runTest {
        val client = InteractionClient()
        val controller = BusinessWorkbenchController(client)
        controller.load(7)

        client.sortFailure = IllegalStateException("network")
        controller.updateSort(BusinessWorkbenchSortKind.SHORTCUT, listOf("shortcut-2", "shortcut-1"))
        assertEquals(listOf("shortcut-1", "shortcut-2"), controller.state.value.shortcutOrder)
        assertTrue(controller.state.value.sortError?.isNotBlank() == true)

        client.sortFailure = null
        client.canonicalSort = listOf("shortcut-2", "shortcut-1")
        controller.updateSort(BusinessWorkbenchSortKind.SHORTCUT, listOf("shortcut-2", "shortcut-1"))
        assertEquals(listOf("shortcut-2", "shortcut-1"), controller.state.value.shortcutOrder)
        assertEquals(1, controller.state.value.shortcutSortRevision)
        assertNull(controller.state.value.sortError)
    }

    @Test
    fun `shortcut and summary use independent revisions`() = runTest {
        val client = InteractionClient()
        val controller = BusinessWorkbenchController(client)
        controller.load(7)

        controller.updateSort(BusinessWorkbenchSortKind.SHORTCUT, listOf("shortcut-2", "shortcut-1"))
        controller.updateSort(BusinessWorkbenchSortKind.SUMMARY, listOf("1006", "1007"))

        assertEquals(0, client.sortRequests[0].expectedRevision)
        assertEquals(0, client.sortRequests[1].expectedRevision)
        assertEquals(1, controller.state.value.shortcutSortRevision)
        assertEquals(1, controller.state.value.summarySortRevision)
    }

    @Test
    fun `refresh failure rolls optimistic sort back and exposes an explicit error`() = runTest {
        val client = InteractionClient()
        val controller = BusinessWorkbenchController(client)
        controller.load(7)
        client.refreshSort = true
        client.getFailure = IllegalStateException("refresh failed")

        controller.updateSort(BusinessWorkbenchSortKind.SHORTCUT, listOf("shortcut-2", "shortcut-1"))

        assertEquals(listOf("shortcut-1", "shortcut-2"), controller.state.value.shortcutOrder)
        assertTrue(controller.state.value.sortError?.contains("refresh failed") == true)
    }

    @Test
    fun `summary sort failure rolls back independently`() = runTest {
        val client = InteractionClient()
        val controller = BusinessWorkbenchController(client)
        controller.load(7)
        client.sortFailure = IllegalStateException("summary failed")

        controller.updateSort(BusinessWorkbenchSortKind.SUMMARY, listOf("1006", "1007"))

        assertEquals(listOf("1007", "1006", "1003", "1004"), controller.state.value.summaryOrder)
        assertTrue(controller.state.value.sortError?.contains("summary failed") == true)
        assertEquals(0, controller.state.value.summarySortRevision)
    }

    @Test
    fun `overlapping sort request is ignored while the first mutation is in flight`() = runTest {
        val client = InteractionClient()
        val controller = BusinessWorkbenchController(client)
        controller.load(7)
        val gate = CompletableDeferred<Unit>()
        client.sortGate = gate

        val first = launch {
            controller.updateSort(BusinessWorkbenchSortKind.SHORTCUT, listOf("shortcut-2", "shortcut-1"))
        }
        kotlinx.coroutines.yield()
        controller.updateSort(BusinessWorkbenchSortKind.SUMMARY, listOf("1006", "1007", "1003", "1004"))

        assertEquals(1, client.sortRequests.size)
        gate.complete(Unit)
        first.join()
    }

    @Test
    fun `missing or invalid team falls back to ALL and loads the ALL page`() = runTest {
        val client = InteractionClient()
        client.teamsJson = "[]"
        val controller = BusinessWorkbenchController(client)
        controller.load(7)

        controller.changeScope(BusinessWorkbenchScope.TEAM)
        controller.changeTeam("removed-team")

        assertEquals(2, client.pageRequests.size)
        assertTrue(client.pageRequests.all { it.scope == BusinessWorkbenchScope.ALL })
    }

    @Test
    fun `new generation invalidates roles even when the same team remains visible`() {
        val reducer = BusinessWorkbenchReducer()
        var state = BusinessWorkbenchState(
            loadState = BusinessWorkbenchLoadState.READY,
            identityEpoch = 7,
            generation = 8,
            scope = BusinessWorkbenchScope.TEAM,
            teamId = "team-1",
            roleCode = "OWNER",
            roles = listOf(BusinessWorkbenchTeamRole("OWNER", "负责人")),
            shortcutSortRevision = 8,
            summarySortRevision = 6,
        )
        state = reducer.reduce(state, BusinessWorkbenchEvent.LoadStarted(4, 7))
        state = reducer.reduce(
            state,
            BusinessWorkbenchEvent.Loaded(
                4,
                BusinessWorkbenchSnapshot(
                    identityEpoch = 7,
                    generation = 9,
                    teams = BusinessWorkbenchSection(
                        BusinessWorkbenchSectionStatus.OK,
                        Json.parseToJsonElement("""[{"id":"team-1"}]"""),
                    ),
                ),
                emptyList(),
            ),
        )

        assertEquals(emptyList(), state.roles)
        assertNull(state.roleCode)
        assertEquals(0, state.shortcutSortRevision)
        assertEquals(0, state.summarySortRevision)
    }

    @Test
    fun `old generation sort failure cannot roll a newly loaded snapshot back`() {
        val reducer = BusinessWorkbenchReducer()
        var state = BusinessWorkbenchState(
            loadState = BusinessWorkbenchLoadState.READY,
            identityEpoch = 7,
            generation = 9,
            shortcutOrder = listOf("old-1", "old-2"),
            shortcutSortRevision = 4,
            sortLoading = true,
            loadRequestId = 40,
            sortRequestId = 30,
        )
        state = reducer.reduce(
            state,
            BusinessWorkbenchEvent.Loaded(
                requestId = 40,
                snapshot = BusinessWorkbenchSnapshot(
                    identityEpoch = 7,
                    generation = 10,
                    shortcuts = BusinessWorkbenchSection(
                        BusinessWorkbenchSectionStatus.OK,
                        Json.parseToJsonElement(
                            """[
                            {"id":"new-1","path":"/case","enabled":true},
                            {"id":"new-2","path":"/team","enabled":true}
                            ]""",
                        ),
                    ),
                ),
                navigation = emptyList(),
            ),
        )
        val newSnapshotState = state

        assertEquals(
            newSnapshotState,
            reducer.reduce(
                state,
                BusinessWorkbenchEvent.SortStarted(
                    requestId = 31,
                    identityEpoch = 7,
                    generation = 9,
                    kind = BusinessWorkbenchSortKind.SHORTCUT,
                    optimisticIds = listOf("old-2", "old-1"),
                ),
            ),
        )
        assertEquals(
            newSnapshotState,
            reducer.reduce(
                state,
                BusinessWorkbenchEvent.SortSucceeded(
                    requestId = 30,
                    identityEpoch = 7,
                    generation = 9,
                    kind = BusinessWorkbenchSortKind.SHORTCUT,
                    revision = 5,
                    canonicalIds = listOf("old-2", "old-1"),
                ),
            ),
        )
        state = reducer.reduce(
            state,
            BusinessWorkbenchEvent.SortFailed(
                requestId = 30,
                identityEpoch = 7,
                generation = 9,
                kind = BusinessWorkbenchSortKind.SHORTCUT,
                rollbackIds = listOf("old-1", "old-2"),
                message = "late failure",
            ),
        )

        assertEquals(newSnapshotState, state)
        assertEquals(listOf("new-1", "new-2"), state.shortcutOrder)
        assertEquals(0, state.shortcutSortRevision)
        assertNull(state.sortError)
    }

    @Test
    fun `kind scope team and role changes reset page and invalidate dependent role state`() {
        val reducer = BusinessWorkbenchReducer()
        val page = BusinessWorkbenchPage(7, 9, 1, 4, 20, emptyList())
        val base = BusinessWorkbenchState(
            loadState = BusinessWorkbenchLoadState.READY,
            identityEpoch = 7,
            generation = 9,
            page = page,
            pageNo = 4,
            scope = BusinessWorkbenchScope.TEAM,
            teamId = "team-1",
            roleCode = "OWNER",
            roles = listOf(BusinessWorkbenchTeamRole("OWNER", "负责人")),
        )

        val kindChanged = reducer.reduce(base, BusinessWorkbenchEvent.KindChanged(BusinessWorkbenchKind.VISIT))
        assertEquals(1, kindChanged.pageNo)
        assertNull(kindChanged.page)
        assertNull(kindChanged.roleCode)
        assertEquals(emptyList(), kindChanged.roles)

        val teamChanged = reducer.reduce(
            base,
            BusinessWorkbenchEvent.ScopeChanged(BusinessWorkbenchScope.TEAM, "team-2", null),
        )
        assertEquals(1, teamChanged.pageNo)
        assertNull(teamChanged.page)
        assertEquals("team-2", teamChanged.teamId)
        assertEquals(emptyList(), teamChanged.roles)

        val roleChanged = reducer.reduce(
            base,
            BusinessWorkbenchEvent.ScopeChanged(BusinessWorkbenchScope.TEAM, "team-1", "MEMBER"),
        )
        assertEquals(1, roleChanged.pageNo)
        assertNull(roleChanged.page)
        assertEquals("MEMBER", roleChanged.roleCode)
        assertEquals(base.roles, roleChanged.roles)

        val allScope = reducer.reduce(
            base,
            BusinessWorkbenchEvent.ScopeChanged(BusinessWorkbenchScope.ALL, null, null),
        )
        assertEquals(1, allScope.pageNo)
        assertNull(allScope.teamId)
        assertNull(allScope.roleCode)
        assertEquals(emptyList(), allScope.roles)
    }

    @Test
    fun `stale page and role responses are discarded by request epoch generation team and kind`() {
        val reducer = BusinessWorkbenchReducer()
        val base = BusinessWorkbenchState(
            loadState = BusinessWorkbenchLoadState.READY,
            identityEpoch = 7,
            generation = 9,
            selectedKind = BusinessWorkbenchKind.CASE,
            scope = BusinessWorkbenchScope.TEAM,
            teamId = "team-1",
            pageRequestId = 10,
            rolesRequestId = 20,
            pageLoading = true,
            rolesLoading = true,
        )
        val wrongRequestPage = reducer.reduce(
            base,
            BusinessWorkbenchEvent.PageLoaded(
                requestId = 9,
                identityEpoch = 7,
                page = BusinessWorkbenchPage(7, 9, 1, 1, 20, emptyList()),
            ),
        )
        val wrongGenerationPage = reducer.reduce(
            base,
            BusinessWorkbenchEvent.PageLoaded(
                requestId = 10,
                identityEpoch = 7,
                page = BusinessWorkbenchPage(7, 10, 1, 1, 20, emptyList()),
            ),
        )
        val staleRoles = reducer.reduce(
            base,
            BusinessWorkbenchEvent.RolesLoaded(
                requestId = 20,
                identityEpoch = 7,
                generation = 10,
                teamId = "team-2",
                kind = BusinessWorkbenchKind.VISIT,
                roles = listOf(BusinessWorkbenchTeamRole("OWNER", "负责人")),
            ),
        )

        assertEquals(base, wrongRequestPage)
        assertEquals(base, wrongGenerationPage)
        assertEquals(base, staleRoles)
    }

    private class InteractionClient : BusinessWorkbenchClient {
        val pageRequests = mutableListOf<BusinessWorkbenchPageRequest>()
        val roleRequests = mutableListOf<Pair<String, BusinessWorkbenchKind>>()
        val sortRequests = mutableListOf<BusinessWorkbenchSortRequest>()
        var sortFailure: Throwable? = null
        var getFailure: Throwable? = null
        var refreshSort = false
        var sortGate: CompletableDeferred<Unit>? = null
        var canonicalSort = listOf("shortcut-1", "shortcut-2")
        var teamsJson = """[{"id":"team-1","name":"第一团队"}]"""

        override suspend fun get(month: String?, day: String?): BusinessWorkbenchSnapshot {
            getFailure?.let { throw it }
            return BusinessWorkbenchSnapshot(
                identityEpoch = 7,
                generation = 9,
                shortcuts = section(
                    """[
                        {"id":"shortcut-1","title":"案件","path":"/case","enabled":true},
                        {"id":"shortcut-2","title":"团队","path":"/team","enabled":true}
                    ]""",
                ),
                summary = section(
                    """[
                        {"id":"1007","configCode":"case_handle","enabled":true},
                        {"id":"1006","configCode":"appointment","enabled":true},
                        {"id":"1003","configCode":"counselor","enabled":true},
                        {"id":"1004","configCode":"visit","enabled":true}
                    ]""",
                ),
                teams = section(teamsJson),
            )
        }

        override suspend fun navigation() = BusinessWorkbenchNavigation(7, 9, emptyList())
        override suspend fun homeInfo() = BusinessWorkbenchSection()

        override suspend fun page(request: BusinessWorkbenchPageRequest): BusinessWorkbenchPage {
            pageRequests += request
            return BusinessWorkbenchPage(7, 9, 0, request.pageNo, request.pageSize, emptyList())
        }

        override suspend fun teamRoles(teamId: String, kind: BusinessWorkbenchKind): BusinessWorkbenchTeamRoles {
            roleRequests += teamId to kind
            return BusinessWorkbenchTeamRoles(7, 9, listOf(BusinessWorkbenchTeamRole("OWNER", "负责人")))
        }

        override suspend fun updateSort(request: BusinessWorkbenchSortRequest): BusinessWorkbenchSortMutation {
            sortFailure?.let { throw it }
            sortRequests += request
            sortGate?.await()
            return BusinessWorkbenchSortMutation(
                7,
                9,
                request.expectedRevision + 1,
                refreshSort,
                canonicalSort.takeUnless { refreshSort },
            )
        }

        private fun section(json: String) =
            BusinessWorkbenchSection(BusinessWorkbenchSectionStatus.OK, Json.parseToJsonElement(json))
    }
}
