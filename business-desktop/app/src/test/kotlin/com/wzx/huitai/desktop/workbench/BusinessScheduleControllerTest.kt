package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.agent.business.workbench.BusinessScheduleClient
import com.wzx.huitai.agent.business.workbench.BusinessScheduleCompletion
import com.wzx.huitai.agent.business.workbench.BusinessScheduleCreateRequest
import com.wzx.huitai.agent.business.workbench.BusinessScheduleDayData
import com.wzx.huitai.agent.business.workbench.BusinessScheduleDayGroup
import com.wzx.huitai.agent.business.workbench.BusinessScheduleDayItem
import com.wzx.huitai.agent.business.workbench.BusinessScheduleForm
import com.wzx.huitai.agent.business.workbench.BusinessScheduleMutation
import com.wzx.huitai.agent.business.workbench.BusinessScheduleMonthData
import com.wzx.huitai.agent.business.workbench.BusinessScheduleMonthEntry
import com.wzx.huitai.agent.business.workbench.BusinessScheduleQuery
import com.wzx.huitai.agent.business.workbench.BusinessScheduleRelationOptions
import com.wzx.huitai.agent.business.workbench.BusinessScheduleOption
import com.wzx.huitai.agent.business.workbench.BusinessScheduleRelation
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class BusinessScheduleControllerTest {
    @Test
    fun `loads month and day navigates week and applies team onlyMine`() = runTest {
        val client = FakeScheduleClient()
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.TEAM, "team-1")

        controller.load(LocalDate.of(2026, 7, 29))
        controller.setViewMode(BusinessScheduleViewMode.WEEK)
        controller.next()
        controller.previous()
        controller.today(LocalDate.of(2026, 7, 30))
        controller.setOnlyMine(true)

        assertEquals("2026-07", client.monthQueries.first().date)
        assertEquals("2026-07-30", client.dayQueries.last().date)
        assertTrue(client.monthQueries.last().onlyMine)
        assertEquals("team-1", client.monthQueries.last().teamId)
        assertEquals(BusinessScheduleViewMode.WEEK, controller.state.value.viewMode)
    }

    @Test
    fun `uses the typed month count and grouped day contract mapped from real OA DTOs`() = runTest {
        val client = FakeScheduleClient().apply {
            monthDays = listOf(
                BusinessScheduleMonthEntry("2026-07-29", 2),
                BusinessScheduleMonthEntry("2026-07-30", 0),
            )
            dayGroups = listOf(
                BusinessScheduleDayGroup(
                    "上午",
                    false,
                    listOf(
                        BusinessScheduleDayItem(
                            "schedule-1",
                            "客户会议",
                            "2026-07-29 10:00:00",
                            true,
                        ),
                    ),
                ),
            )
        }
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.TEAM, "team-1")

        controller.load(LocalDate.of(2026, 7, 29))

        assertEquals(setOf(LocalDate.of(2026, 7, 29)), controller.state.value.eventDates)
        assertEquals(
            BusinessScheduleItem(
                id = "schedule-1",
                title = "客户会议",
                at = "2026-07-29 10:00:00",
                completed = true,
                groupTime = client.dayGroups.single().time,
            ),
            controller.state.value.items.single(),
        )
    }

    @Test
    fun `completion rolls back on failure and old epoch generation results are discarded`() = runTest {
        val client = FakeScheduleClient()
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        controller.load(LocalDate.of(2026, 7, 29))

        client.completionFailure = IllegalStateException("offline")
        controller.setCompleted("schedule-1", true)
        assertFalse(controller.state.value.items.single().completed)
        assertTrue(controller.state.value.error?.contains("offline") == true)

        val gate = CompletableDeferred<Unit>()
        client.dayGate = gate
        val old = async { controller.selectDate(LocalDate.of(2026, 7, 29)) }
        kotlinx.coroutines.yield()
        controller.attach(8, 10, BusinessWorkbenchScope.PERSONAL, null)
        gate.complete(Unit)
        old.await()
        assertEquals(8, controller.state.value.identityEpoch)
        assertEquals(10, controller.state.value.generation)
        assertNull(controller.state.value.items.firstOrNull())
    }

    @Test
    fun `same identity stale load cannot overwrite the latest selected day`() = runTest {
        val client = FakeScheduleClient()
        val firstGate = CompletableDeferred<Unit>()
        client.dayGates["2026-07-29"] = firstGate
        client.dayGroupsByDate["2026-07-29"] = listOf(
            BusinessScheduleDayGroup(
                "",
                false,
                listOf(BusinessScheduleDayItem("old", "旧日程", "2026-07-29 10:00:00", false)),
            ),
        )
        client.dayGroupsByDate["2026-07-30"] = listOf(
            BusinessScheduleDayGroup(
                "",
                false,
                listOf(BusinessScheduleDayItem("new", "新日程", "2026-07-30 10:00:00", false)),
            ),
        )
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)

        val old = async { controller.load(LocalDate.of(2026, 7, 29)) }
        kotlinx.coroutines.yield()
        controller.load(LocalDate.of(2026, 7, 30))
        firstGate.complete(Unit)
        old.await()

        assertEquals(LocalDate.of(2026, 7, 30), controller.state.value.selectedDate)
        assertEquals("new", controller.state.value.items.single().id)
    }

    @Test
    fun `old completion failure cannot restore the previous identity state`() = runTest {
        val client = FakeScheduleClient()
        val gate = CompletableDeferred<Unit>()
        client.completionGate = gate
        client.completionFailure = IllegalStateException("old-offline")
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        controller.load(LocalDate.of(2026, 7, 29))

        val old = async { controller.setCompleted("schedule-1", true) }
        kotlinx.coroutines.yield()
        controller.attach(8, 10, BusinessWorkbenchScope.TEAM, "team-2")
        gate.complete(Unit)
        old.await()

        assertEquals(8, controller.state.value.identityEpoch)
        assertEquals(10, controller.state.value.generation)
        assertNull(controller.state.value.error)
        assertTrue(controller.state.value.items.isEmpty())
    }

    @Test
    fun `completion requests for different schedules roll back independently`() = runTest {
        val client = FakeScheduleClient().apply {
            dayGroups = listOf(
                BusinessScheduleDayGroup(
                    "",
                    false,
                    listOf(
                        BusinessScheduleDayItem(
                            "schedule-1",
                            "First",
                            "2026-07-29 10:00:00",
                            false,
                        ),
                        BusinessScheduleDayItem(
                            "schedule-2",
                            "Second",
                            "2026-07-29 11:00:00",
                            false,
                        ),
                    ),
                ),
            )
            completionGates["schedule-1"] = CompletableDeferred()
            completionFailures["schedule-1"] = IllegalStateException("first-offline")
            completionRefreshRequired = false
        }
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        controller.load(LocalDate.of(2026, 7, 29))

        val first = async { controller.setCompleted("schedule-1", true) }
        kotlinx.coroutines.yield()
        controller.setCompleted("schedule-2", true)
        assertTrue(controller.state.value.items.all { it.completed })
        client.completionGates.getValue("schedule-1").complete(Unit)
        first.await()

        assertFalse(controller.state.value.items.first { it.id == "schedule-1" }.completed)
        assertTrue(controller.state.value.items.first { it.id == "schedule-2" }.completed)
        assertTrue(controller.state.value.error?.contains("first-offline") == true)
    }

    @Test
    fun `later completion for the same schedule supersedes an older failure`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val client = FakeScheduleClient().apply {
            completionBehaviors["schedule-1"] = ArrayDeque(
                listOf(
                    CompletionBehavior(firstGate, IllegalStateException("stale-failure"), false),
                    CompletionBehavior(refreshRequired = false),
                ),
            )
        }
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        controller.load(LocalDate.of(2026, 7, 29))

        val older = async { controller.setCompleted("schedule-1", true) }
        kotlinx.coroutines.yield()
        controller.setCompleted("schedule-1", false)
        firstGate.complete(Unit)
        older.await()

        assertFalse(controller.state.value.items.single().completed)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `old form and relation failures cannot mutate a new identity form`() = runTest {
        val client = FakeScheduleClient()
        val formGate = CompletableDeferred<Unit>()
        client.formGate = formGate
        client.formFailure = IllegalStateException("old-form")
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        val oldForm = async { controller.openCreate() }
        kotlinx.coroutines.yield()
        controller.attach(8, 10, BusinessWorkbenchScope.TEAM, "team-2")
        formGate.complete(Unit)
        oldForm.await()
        assertFalse(controller.formState.value.visible)
        assertNull(controller.formState.value.error)

        client.formFailure = null
        client.formResult = BusinessScheduleForm(8, 10, 1, emptyList(), emptyList())
        controller.openCreate()
        val relationGate = CompletableDeferred<Unit>()
        client.relationGate = relationGate
        client.relationFailure = IllegalStateException("old-relation")
        val oldRelation = async { controller.loadRelationOptions(BusinessScheduleRelationType.CASE) }
        kotlinx.coroutines.yield()
        controller.attach(9, 11, BusinessWorkbenchScope.PERSONAL, null)
        relationGate.complete(Unit)
        oldRelation.await()
        assertNull(controller.formState.value.error)
        assertNull(controller.formState.value.selectedRelationType)
    }

    @Test
    fun `old create response cannot close or reload a new identity form`() = runTest {
        val client = FakeScheduleClient()
        val createGate = CompletableDeferred<Unit>()
        client.createGate = createGate
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        controller.openCreate()
        val oldCreate = async { controller.submit(BusinessScheduleDraft.validForTest()) }
        kotlinx.coroutines.yield()

        controller.attach(8, 10, BusinessWorkbenchScope.TEAM, "team-2")
        client.formResult = BusinessScheduleForm(8, 10, 2, emptyList(), emptyList())
        controller.openCreate()
        assertTrue(controller.formState.value.visible)
        createGate.complete(Unit)
        oldCreate.await()

        assertEquals(8, controller.state.value.identityEpoch)
        assertEquals(10, controller.state.value.generation)
        assertTrue(controller.formState.value.visible)
        assertFalse(controller.formState.value.submitting)
    }

    @Test
    fun `create uses loaded form relations and attachment batch once without replay`() = runTest {
        val client = FakeScheduleClient()
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        controller.openCreate()
        controller.loadRelationOptions(BusinessScheduleRelationType.CASE, "合同")
        client.createFailure = IllegalStateException("outcome unknown")

        controller.submit(BusinessScheduleDraft.validForTest(attachmentBatchId = "batch-1"))
        assertEquals(1, client.createRequests.size)
        assertTrue(controller.formState.value.error?.contains("outcome unknown") == true)
        assertFalse(controller.formState.value.submitting)
    }

    @Test
    fun `week navigation crosses year and completion refreshes canonical day`() = runTest {
        val client = FakeScheduleClient()
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        controller.load(LocalDate.of(2026, 12, 31))
        controller.setViewMode(BusinessScheduleViewMode.WEEK)

        controller.next()
        assertEquals("2027-01", client.monthQueries.last().date)
        assertEquals("2027-01-07", client.dayQueries.last().date)
        controller.previous()
        assertEquals("2026-12-31", client.dayQueries.last().date)

        val before = client.dayQueries.size
        controller.setCompleted("schedule-1", true)
        assertTrue(client.dayQueries.size > before)
        assertFalse(controller.state.value.items.single().completed)
    }

    @Test
    fun `week mode loads and merges every month touched by monday through sunday`() = runTest {
        val client = FakeScheduleClient().apply {
            monthDaysByDate["2025-12"] = listOf(BusinessScheduleMonthEntry("2025-12-29", 1))
            monthDaysByDate["2026-01"] = listOf(BusinessScheduleMonthEntry("2026-01-02", 2))
        }
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        controller.load(LocalDate.of(2025, 12, 31))
        client.monthQueries.clear()

        controller.setViewMode(BusinessScheduleViewMode.WEEK)

        assertEquals(listOf("2025-12", "2026-01"), client.monthQueries.map { it.date })
        assertEquals(
            setOf(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 2)),
            controller.state.value.eventDates,
        )
        assertEquals(LocalDate.of(2025, 12, 31), controller.state.value.selectedDate)
    }

    @Test
    fun `form authority relation options create mapping and clear are typed`() = runTest {
        val client = FakeScheduleClient()
        client.formResult = BusinessScheduleForm(
            7,
            9,
            12,
            listOf(BusinessScheduleOption("type-1", "会议")),
            listOf(BusinessScheduleOption("user-1", "当前用户")),
        )
        client.relationItems = listOf(BusinessScheduleOption("case-1", "合同纠纷"))
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.TEAM, "team-1")

        controller.openCreate()
        assertFalse(controller.formState.value.canAssignOthers)
        assertEquals(12, controller.formState.value.revision)
        controller.loadRelationOptions(BusinessScheduleRelationType.CASE, "合同")
        assertEquals(BusinessScheduleRelationType.CASE, controller.formState.value.selectedRelationType)
        assertEquals("case-1", controller.formState.value.relationOptions.single().id)

        val draft = BusinessScheduleDraft(
            clientOperationId = "operation-stable",
            scope = BusinessWorkbenchScope.TEAM,
            teamId = "team-1",
            assigneeUserId = "user-1",
            title = "客户会议",
            typeId = "type-1",
            at = "2026-12-31 10:00:00",
            priority = 4,
            reminderMinutes = listOf(10, 45),
            repeatRule = "WEEKLY",
            relations = listOf(BusinessScheduleRelation("CASE", "case-1", "合同纠纷")),
        )
        controller.submit(draft)
        val sent = client.createRequests.single()
        assertEquals("operation-stable", sent.clientOperationId)
        assertEquals(2, sent.repetition)
        assertEquals(12, sent.formRevision)
        assertEquals("case-1", sent.relations.single().id)

        controller.clear()
        assertEquals(0, controller.state.value.identityEpoch)
        assertFalse(controller.formState.value.visible)
    }

    @Test
    fun `invalid create fields fail locally before the non idempotent RPC`() = runTest {
        val client = FakeScheduleClient()
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.TEAM, "team-1")
        controller.openCreate()

        controller.submit(
            BusinessScheduleDraft.validForTest().copy(
                scope = BusinessWorkbenchScope.TEAM,
                teamId = "team-1",
                assigneeUserId = null,
                title = " ",
                typeId = "",
                at = "not-a-date",
                priority = 5,
                reminderMinutes = listOf(0),
            ),
        )

        assertTrue(client.createRequests.isEmpty())
        assertFalse(controller.formState.value.submitting)
        assertTrue(controller.formState.value.error?.isNotBlank() == true)
    }

    @Test
    fun `visit and service associations force repetition to none before create`() = runTest {
        val client = FakeScheduleClient()
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        controller.openCreate()

        controller.submit(
            BusinessScheduleDraft.validForTest().copy(
                repeatRule = "WEEKLY",
                relations = listOf(
                    BusinessScheduleRelation("VISIT", "visit-1", "上门拜访"),
                ),
            ),
        )

        assertEquals(1, client.createRequests.size)
        assertEquals(0, client.createRequests.single().repetition)
    }

    @Test
    fun `service association selects a record then its project and replaces prior association`() = runTest {
        val client = FakeScheduleClient().apply {
            relationItems = listOf(BusinessScheduleOption("record-1", "常年顾问"))
            serviceProjectItems = listOf(BusinessScheduleOption("project-1", "合同审查"))
        }
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.TEAM, "team-1")
        controller.openCreate()

        controller.loadRelationOptions(BusinessScheduleRelationType.SERVICE)
        val record = controller.formState.value.relationOptions.single()
        controller.selectRelationOption(BusinessScheduleRelationType.SERVICE, record)

        assertEquals(listOf("record-1"), client.serviceProjectRequests)
        assertEquals(listOf<String?>("team-1"), client.serviceProjectTeamIds)
        assertEquals("record-1", controller.formState.value.selectedServiceRecord?.id)
        val project = controller.formState.value.relationOptions.single()
        controller.selectRelationOption(BusinessScheduleRelationType.SERVICE, project)
        assertEquals(
            BusinessScheduleRelation("SERVICE", "project-1", "合同审查", "record-1"),
            controller.formState.value.draft.relations.single(),
        )

        client.relationItems = listOf(BusinessScheduleOption("case-1", "合同纠纷"))
        controller.loadRelationOptions(BusinessScheduleRelationType.CASE)
        controller.selectRelationOption(
            BusinessScheduleRelationType.CASE,
            controller.formState.value.relationOptions.single(),
        )
        assertEquals(
            listOf(BusinessScheduleRelation("CASE", "case-1", "合同纠纷")),
            controller.formState.value.draft.relations,
        )
    }

    @Test
    fun `switching relation type discards a late service project response`() = runTest {
        val client = FakeScheduleClient().apply {
            relationItems = listOf(BusinessScheduleOption("record-1", "常年顾问"))
            serviceProjectItems = listOf(BusinessScheduleOption("project-1", "合同审查"))
            serviceProjectGate = CompletableDeferred()
        }
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        controller.openCreate()
        controller.loadRelationOptions(BusinessScheduleRelationType.SERVICE)

        val lateService = async {
            controller.selectRelationOption(
                BusinessScheduleRelationType.SERVICE,
                controller.formState.value.relationOptions.single(),
            )
        }
        kotlinx.coroutines.yield()
        client.relationItems = listOf(BusinessScheduleOption("case-1", "合同纠纷"))
        controller.loadRelationOptions(BusinessScheduleRelationType.CASE)
        client.serviceProjectGate?.complete(Unit)
        lateService.await()

        assertEquals(BusinessScheduleRelationType.CASE, controller.formState.value.selectedRelationType)
        assertNull(controller.formState.value.selectedServiceRecord)
        assertEquals(listOf("case-1"), controller.formState.value.relationOptions.map { it.id })
        assertTrue(controller.formState.value.draft.relations.isEmpty())
    }

    @Test
    fun `attachment parent requires exactly the current single association`() {
        val one = BusinessScheduleDraft.validForTest().copy(
            relations = listOf(BusinessScheduleRelation("CASE", "case-1")),
        )
        val ambiguous = one.copy(
            relations = one.relations + BusinessScheduleRelation("CUSTOMER", "customer-1"),
        )

        assertEquals("case-1", one.currentAttachmentParent()?.id)
        assertNull(ambiguous.currentAttachmentParent())
    }

    @Test
    fun `old identity attachment success and cancellation failure cannot mutate the new form`() {
        val controller = BusinessScheduleController(FakeScheduleClient())
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        val old = controller.beginAttachmentUpload()

        controller.attach(8, 10, BusinessWorkbenchScope.PERSONAL, null)
        val newDraft = controller.formState.value.draft

        assertFalse(
            controller.completeAttachmentUpload(
                old,
                listOf("old.pdf"),
                attachmentBatchId = "old-batch",
                attachmentParentResourceId = "old-project",
                attachmentParentRelationType = "SERVICE",
            ),
        )
        controller.failAttachmentUpload(old, "old upload cancelled")

        assertTrue(controller.formState.value.attachmentNames.isEmpty())
        assertNull(controller.formState.value.draft.attachmentBatchId)
        assertNull(controller.formState.value.error)
    }

    @Test
    fun `attachment completion merges into current draft without overwriting edits made during upload`() {
        val controller = BusinessScheduleController(FakeScheduleClient())
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        val initial = BusinessScheduleDraft.validForTest().copy(
            title = "before upload",
            relations = listOf(BusinessScheduleRelation("CASE", "case-1")),
        )
        controller.updateDraft(initial)
        val request = controller.beginAttachmentUpload()
        controller.updateDraft(controller.formState.value.draft.copy(title = "edited while uploading"))

        assertTrue(
            controller.completeAttachmentUpload(
                request,
                listOf("proof.pdf"),
                attachmentBatchId = "batch-1",
                attachmentParentResourceId = "case-1",
                attachmentParentRelationType = "CASE",
            ),
        )

        assertEquals("edited while uploading", controller.formState.value.draft.title)
        assertEquals("batch-1", controller.formState.value.draft.attachmentBatchId)
        assertEquals(listOf("proof.pdf"), controller.formState.value.attachmentNames)
    }

    @Test
    fun `attachment completion is discarded after operation or relation drifts`() {
        val controller = BusinessScheduleController(FakeScheduleClient())
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)
        val initial = BusinessScheduleDraft.validForTest().copy(
            relations = listOf(BusinessScheduleRelation("CASE", "case-1")),
        )
        controller.updateDraft(initial)
        val relationRequest = controller.beginAttachmentUpload()
        controller.updateDraft(
            controller.formState.value.draft.copy(
                relations = listOf(BusinessScheduleRelation("CASE", "case-2")),
            ),
        )
        assertFalse(
            controller.completeAttachmentUpload(
                relationRequest,
                listOf("late.pdf"),
                attachmentBatchId = "late-batch",
                attachmentParentResourceId = "case-1",
                attachmentParentRelationType = "CASE",
            ),
        )

        controller.updateDraft(initial)
        val operationRequest = controller.beginAttachmentUpload()
        controller.updateDraft(controller.formState.value.draft.copy(clientOperationId = "new-operation"))
        assertFalse(
            controller.completeAttachmentUpload(
                operationRequest,
                listOf("late.pdf"),
                attachmentBatchId = "late-batch",
                attachmentParentResourceId = "case-1",
                attachmentParentRelationType = "CASE",
            ),
        )
        assertNull(controller.formState.value.draft.attachmentBatchId)
        assertTrue(controller.formState.value.attachmentNames.isEmpty())
    }

    @Test
    fun `scope team or type changes discard bound attachments and invalidate an active upload`() {
        val bindingChanges: List<(BusinessScheduleDraft) -> BusinessScheduleDraft> = listOf(
            { it.copy(scope = BusinessWorkbenchScope.PERSONAL, teamId = null) },
            { it.copy(teamId = "team-2") },
            { it.copy(typeId = "type-2") },
        )

        bindingChanges.forEachIndexed { index, change ->
            val controller = BusinessScheduleController(FakeScheduleClient())
            controller.attach(7, 9, BusinessWorkbenchScope.TEAM, "team-1")
            val base = BusinessScheduleDraft.validForTest().copy(
                clientOperationId = "binding-$index",
                scope = BusinessWorkbenchScope.TEAM,
                teamId = "team-1",
                typeId = "type-1",
                relations = listOf(BusinessScheduleRelation("CASE", "case-1")),
            )
            controller.updateDraft(base)
            val completed = controller.beginAttachmentUpload()
            assertTrue(
                controller.completeAttachmentUpload(
                    completed,
                    listOf("proof.pdf"),
                    attachmentBatchId = "batch-$index",
                    attachmentParentResourceId = "case-1",
                    attachmentParentRelationType = "CASE",
                ),
            )
            val active = controller.beginAttachmentUpload()

            controller.updateDraft(change(controller.formState.value.draft))

            assertNull(controller.formState.value.draft.attachmentBatchId)
            assertNull(controller.formState.value.draft.attachmentParentResourceId)
            assertNull(controller.formState.value.draft.attachmentParentRelationType)
            assertTrue(controller.formState.value.attachmentNames.isEmpty())
            assertFalse(
                controller.completeAttachmentUpload(
                    active,
                    listOf("late.pdf"),
                    attachmentBatchId = "late-$index",
                    attachmentParentResourceId = "case-1",
                    attachmentParentRelationType = "CASE",
                ),
            )
        }
    }

    @Test
    fun `all day reminders allow OA negative presets and normalize create time to midnight`() = runTest {
        val client = FakeScheduleClient()
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)

        controller.submit(
            BusinessScheduleDraft.validForTest().copy(
                allDay = true,
                at = "2026-07-29 10:45:00",
                reminderMinutes = listOf(-540, 240),
            ),
        )

        assertEquals("2026-07-29 00:00:00", client.createRequests.single().at)
        assertEquals(listOf(-540, 240), client.createRequests.single().reminderMinutes)
    }

    @Test
    fun `negative reminder is rejected for non all day schedule`() = runTest {
        val client = FakeScheduleClient()
        val controller = BusinessScheduleController(client)
        controller.attach(7, 9, BusinessWorkbenchScope.PERSONAL, null)

        controller.submit(
            BusinessScheduleDraft.validForTest().copy(
                allDay = false,
                reminderMinutes = listOf(-540),
            ),
        )

        assertTrue(client.createRequests.isEmpty())
        assertTrue(controller.formState.value.error != null)
    }

    private class FakeScheduleClient : BusinessScheduleClient {
        val monthQueries = mutableListOf<BusinessScheduleQuery>()
        val dayQueries = mutableListOf<BusinessScheduleQuery>()
        val createRequests = mutableListOf<BusinessScheduleCreateRequest>()
        var completionFailure: Throwable? = null
        var completionGate: CompletableDeferred<Unit>? = null
        val completionFailures = mutableMapOf<String, Throwable>()
        val completionGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val completionBehaviors = mutableMapOf<String, ArrayDeque<CompletionBehavior>>()
        var completionRefreshRequired = true
        var createFailure: Throwable? = null
        var createGate: CompletableDeferred<Unit>? = null
        var dayGate: CompletableDeferred<Unit>? = null
        val dayGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var monthDays = listOf(BusinessScheduleMonthEntry("2026-07-29", 1))
        val monthDaysByDate = mutableMapOf<String, List<BusinessScheduleMonthEntry>>()
        var dayGroups = listOf(
            BusinessScheduleDayGroup(
                "",
                false,
                listOf(
                    BusinessScheduleDayItem(
                        "schedule-1",
                        "客户会议",
                        "2026-07-29 10:00:00",
                        false,
                    ),
                ),
            ),
        )
        val dayGroupsByDate = mutableMapOf<String, List<BusinessScheduleDayGroup>>()
        var formResult = BusinessScheduleForm(7, 9, 3, emptyList(), emptyList())
        var formGate: CompletableDeferred<Unit>? = null
        var formFailure: Throwable? = null
        var relationItems = emptyList<BusinessScheduleOption>()
        var relationGate: CompletableDeferred<Unit>? = null
        var relationFailure: Throwable? = null
        var serviceProjectItems = emptyList<BusinessScheduleOption>()
        val serviceProjectRequests = mutableListOf<String>()
        val serviceProjectTeamIds = mutableListOf<String?>()
        var serviceProjectGate: CompletableDeferred<Unit>? = null

        override suspend fun month(query: BusinessScheduleQuery): BusinessScheduleMonthData {
            monthQueries += query
            return BusinessScheduleMonthData(7, 9, monthDaysByDate[query.date] ?: monthDays)
        }

        override suspend fun day(query: BusinessScheduleQuery): BusinessScheduleDayData {
            dayQueries += query
            dayGate?.await()
            dayGates[query.date]?.await()
            return BusinessScheduleDayData(7, 9, dayGroupsByDate[query.date] ?: dayGroups)
        }

        override suspend fun setCompletion(id: String, completed: Boolean): BusinessScheduleCompletion {
            val behavior = completionBehaviors[id]?.removeFirstOrNull()
            behavior?.gate?.await()
            behavior?.failure?.let { throw it }
            if (behavior != null) {
                return BusinessScheduleCompletion(7, 9, completed, behavior.refreshRequired, 1)
            }
            completionGates[id]?.await()
            completionGate?.await()
            completionFailures[id]?.let { throw it }
            completionFailure?.let { throw it }
            return BusinessScheduleCompletion(7, 9, completed, completionRefreshRequired, 1)
        }

        override suspend fun form(scope: BusinessWorkbenchScope, teamId: String?): BusinessScheduleForm {
            formGate?.await()
            formFailure?.let { throw it }
            return formResult
        }

        override suspend fun relationOptions(
            type: String, keyword: String?, teamId: String?, parentId: String?,
        ): BusinessScheduleRelationOptions {
            relationGate?.await()
            relationFailure?.let { throw it }
            return BusinessScheduleRelationOptions(7, 9, 1, type, relationItems)
        }

        override suspend fun serviceProjects(
            recordId: String,
            keyword: String?,
            teamId: String?,
        ): BusinessScheduleRelationOptions {
            serviceProjectRequests += recordId
            serviceProjectTeamIds += teamId
            serviceProjectGate?.await()
            return BusinessScheduleRelationOptions(7, 9, 1, "SERVICE_PROJECT", serviceProjectItems)
        }

        override suspend fun create(request: BusinessScheduleCreateRequest): BusinessScheduleMutation {
            createRequests += request
            createGate?.await()
            createFailure?.let { throw it }
            return BusinessScheduleMutation(7, 9, 1, true)
        }
    }

    private data class CompletionBehavior(
        val gate: CompletableDeferred<Unit>? = null,
        val failure: Throwable? = null,
        val refreshRequired: Boolean = true,
    )
}
