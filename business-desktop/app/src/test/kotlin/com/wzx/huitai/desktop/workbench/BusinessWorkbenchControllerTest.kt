package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.agent.business.auth.BusinessNavigationTarget
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchClient
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchNavigation
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPage
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPageRequest
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSection
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSnapshot
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortMutation
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortRequest
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchTeamRoles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class BusinessWorkbenchControllerTest {
    @Test
    fun `load uses typed client and exposes snapshot and navigation`() = runTest {
        val client = FakeClient()
        val controller = BusinessWorkbenchController(client)

        controller.load(identityEpoch = 6)

        assertEquals(BusinessWorkbenchLoadState.READY, controller.state.value.loadState)
        assertEquals(6, controller.state.value.identityEpoch)
        assertEquals("/", controller.state.value.navigation.single().path)
        assertEquals(1, client.snapshotCalls)
        assertEquals(1, client.navigationCalls)
    }

    @Test
    fun `clear invalidates state and future page response is scoped to the new epoch`() = runTest {
        val client = FakeClient()
        val controller = BusinessWorkbenchController(client)
        controller.load(identityEpoch = 2)
        controller.clear()

        assertEquals(BusinessWorkbenchLoadState.IDLE, controller.state.value.loadState)
        assertEquals(null, controller.state.value.identityEpoch)
        assertEquals(0, controller.state.value.navigation.size)
        assertEquals(1, client.snapshotCalls)
    }

    @Test
    fun `load rejects navigation from a different generation instead of combining snapshots`() = runTest {
        val client = FakeClient(navigationGeneration = 2)
        val controller = BusinessWorkbenchController(client)

        controller.load(identityEpoch = 6)

        assertEquals(BusinessWorkbenchLoadState.ERROR, controller.state.value.loadState)
        assertEquals(null, controller.state.value.snapshot)
        assertEquals(emptyList(), controller.state.value.navigation)
    }

    private class FakeClient(private val navigationGeneration: Long = 1) : BusinessWorkbenchClient {
        var snapshotCalls = 0
        var navigationCalls = 0

        override suspend fun get(month: String?, day: String?): BusinessWorkbenchSnapshot {
            snapshotCalls++
            return BusinessWorkbenchSnapshot(identityEpoch = 6, generation = 1)
        }

        override suspend fun navigation(): BusinessWorkbenchNavigation {
            navigationCalls++
            return BusinessWorkbenchNavigation(
                6,
                navigationGeneration,
                listOf(BusinessNavigationTarget("WORKBENCH", "/", "工作台")),
            )
        }

        override suspend fun homeInfo(): BusinessWorkbenchSection = BusinessWorkbenchSection()
        override suspend fun teamRoles(teamId: String, kind: BusinessWorkbenchKind): BusinessWorkbenchTeamRoles =
            BusinessWorkbenchTeamRoles(6, 1, emptyList())
        override suspend fun updateSort(request: BusinessWorkbenchSortRequest): BusinessWorkbenchSortMutation =
            BusinessWorkbenchSortMutation(6, 1, request.expectedRevision + 1, false, request.ids)
        override suspend fun page(request: BusinessWorkbenchPageRequest): BusinessWorkbenchPage =
            BusinessWorkbenchPage(6, 1, 0, request.pageNo, request.pageSize, emptyList())
    }
}
