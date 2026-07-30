package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.agent.business.auth.BusinessNavigationTarget
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPage
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPageItem
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSection
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSectionStatus
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSnapshot
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject

class BusinessWorkbenchReducerTest {
    private val snapshot = BusinessWorkbenchSnapshot(
        identityEpoch = 4,
        generation = 9,
        shortcuts = BusinessWorkbenchSection(BusinessWorkbenchSectionStatus.OK),
        summary = BusinessWorkbenchSection(BusinessWorkbenchSectionStatus.OK),
    )

    @Test
    fun `loaded snapshot enters ready and selects first statistic`() {
        val reducer = BusinessWorkbenchReducer()
        var state = reducer.reduce(BusinessWorkbenchState(), BusinessWorkbenchEvent.LoadStarted(7, 4))
        state = reducer.reduce(
            state,
            BusinessWorkbenchEvent.Loaded(
                requestId = 7,
                snapshot = snapshot,
                navigation = listOf(BusinessNavigationTarget("WORKBENCH", "/", "工作台")),
            ),
        )

        assertEquals(BusinessWorkbenchLoadState.READY, state.loadState)
        assertEquals(4, state.identityEpoch)
        assertEquals(9, state.generation)
        assertEquals(0, state.selectedStatistic)
        assertEquals("/", state.navigation.single().path)
    }

    @Test
    fun `epoch change clears remote data and stale page response cannot repopulate it`() {
        val reducer = BusinessWorkbenchReducer()
        var state = reducer.reduce(BusinessWorkbenchState(), BusinessWorkbenchEvent.LoadStarted(3, 4))
        state = reducer.reduce(state, BusinessWorkbenchEvent.Loaded(3, snapshot, emptyList()))
        state = reducer.reduce(state, BusinessWorkbenchEvent.EpochChanged(5))
        assertEquals(BusinessWorkbenchLoadState.IDLE, state.loadState)
        assertNull(state.snapshot)
        assertFalse(state.navigation.isNotEmpty())

        state = reducer.reduce(state, BusinessWorkbenchEvent.PageStarted(8, 5))
        state = reducer.reduce(
            state,
            BusinessWorkbenchEvent.PageLoaded(
                requestId = 3,
                identityEpoch = 5,
                page = BusinessWorkbenchPage(5, 1, 1, 1, 20, listOf(BusinessWorkbenchPageItem("old"))),
            ),
        )
        assertNull(state.page)
    }

    @Test
    fun `changing scope resets page and preserves selected business kind`() {
        val reducer = BusinessWorkbenchReducer()
        var state = BusinessWorkbenchState(
            identityEpoch = 4,
            selectedKind = BusinessWorkbenchKind.APPOINTMENT,
            page = BusinessWorkbenchPage(4, 1, 2, 3, 20, emptyList()),
            pageNo = 3,
        )
        state = reducer.reduce(
            state,
            BusinessWorkbenchEvent.ScopeChanged(BusinessWorkbenchScope.TEAM, "team-1", "OWNER"),
        )

        assertEquals(BusinessWorkbenchKind.APPOINTMENT, state.selectedKind)
        assertEquals(BusinessWorkbenchScope.TEAM, state.scope)
        assertEquals("team-1", state.teamId)
        assertEquals("OWNER", state.roleCode)
        assertEquals(1, state.pageNo)
        assertNull(state.page)
    }
}
