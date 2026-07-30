package com.wzx.huitai.desktop

import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSnapshot
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchState
import com.wzx.huitai.desktop.workbench.BusinessAttachmentUploadState
import com.wzx.huitai.desktop.workbench.BusinessScheduleFormState
import com.wzx.huitai.desktop.workbench.BusinessScheduleItem
import com.wzx.huitai.desktop.workbench.BusinessScheduleState
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MainScheduleLifecycleTest {
    @Test
    fun `ready and refresh load workbench before attaching the same epoch and canonical generation to schedule`() = runTest {
        val calls = mutableListOf<String>()
        val state = BusinessWorkbenchState(
            identityEpoch = 17,
            generation = 23,
            scope = BusinessWorkbenchScope.TEAM,
            teamId = "team-7",
            snapshot = BusinessWorkbenchSnapshot(identityEpoch = 17, generation = 23),
        )

        loadBusinessWorkbenchAndSchedule(
            identityEpoch = 17,
            loadWorkbench = { calls += "workbench:$it" },
            loadPage = { calls += "page" },
            currentWorkbenchState = { state },
            attachSchedule = { epoch, generation, scope, teamId ->
                calls += "attach:$epoch:$generation:$scope:$teamId"
            },
            loadSchedule = { calls += "schedule" },
        )

        assertEquals(
            listOf(
                "workbench:17",
                "page",
                "attach:17:23:TEAM:team-7",
                "schedule",
            ),
            calls,
        )
    }

    @Test
    fun `scope or team change reattaches schedule to the canonical workbench selection`() = runTest {
        val calls = mutableListOf<String>()
        val state = BusinessWorkbenchState(
            identityEpoch = 31,
            generation = 47,
            scope = BusinessWorkbenchScope.TEAM,
            teamId = "team-9",
            snapshot = BusinessWorkbenchSnapshot(identityEpoch = 31, generation = 47),
        )

        reloadBusinessScheduleFromWorkbench(
            identityEpoch = 31,
            currentWorkbenchState = { state },
            attachSchedule = { epoch, generation, scope, teamId ->
                calls += "$epoch:$generation:$scope:$teamId"
            },
            loadSchedule = { calls += "schedule" },
        )

        assertEquals(listOf("31:47:TEAM:team-9", "schedule"), calls)
    }

    @Test
    fun `ready to ready identity transition clears upload and draft before any new load`() {
        val calls = mutableListOf<String>()

        val current = transitionBusinessScheduleIdentity(
            previousIdentityEpoch = 7,
            nextIdentityEpoch = 8,
            cancelUpload = { calls += "cancel-upload" },
            clearSchedule = { calls += "clear-schedule" },
            clearLocalAttachments = { calls += "clear-attachments" },
        )
        calls += "load-workbench"

        assertEquals(8, current)
        assertEquals(
            listOf("cancel-upload", "clear-schedule", "clear-attachments", "load-workbench"),
            calls,
        )
        transitionBusinessScheduleIdentity(
            previousIdentityEpoch = current,
            nextIdentityEpoch = 8,
            cancelUpload = { calls += "unexpected" },
            clearSchedule = { calls += "unexpected" },
            clearLocalAttachments = { calls += "unexpected" },
        )
        assertEquals(4, calls.size)
    }

    @Test
    fun `missing snapshot never loads schedule and identity teardown clears upload controller and local attachments`() = runTest {
        val calls = mutableListOf<String>()
        loadBusinessWorkbenchAndSchedule(
            identityEpoch = 18,
            loadWorkbench = { calls += "workbench:$it" },
            loadPage = { calls += "page" },
            currentWorkbenchState = { BusinessWorkbenchState(identityEpoch = 18) },
            attachSchedule = { _, _, _, _ -> calls += "attach" },
            loadSchedule = { calls += "schedule" },
        )
        clearBusinessScheduleSession(
            cancelUpload = { calls += "cancel-upload" },
            clearSchedule = { calls += "clear-schedule" },
            clearLocalAttachments = { calls += "clear-attachments" },
        )

        assertEquals(
            listOf(
                "workbench:18",
                "page",
                "cancel-upload",
                "clear-schedule",
                "clear-attachments",
            ),
            calls,
        )
    }
    @Test
    fun `new ready identity first frame cannot project previous schedule form or upload state`() {
        val projection = projectBusinessScheduleUiIdentity(
            activeIdentityEpoch = 7,
            nextIdentityEpoch = 8,
            scheduleState = BusinessScheduleState(
                identityEpoch = 7,
                generation = 9,
                items = listOf(BusinessScheduleItem("old", "old title", "10:00", false)),
            ),
            formState = BusinessScheduleFormState(visible = true, error = "old error"),
            uploadState = BusinessAttachmentUploadState(uploading = true, progress = 0.5f),
        )

        assertTrue(projection.scheduleState.items.isEmpty())
        assertFalse(projection.formState.visible)
        assertNull(projection.formState.error)
        assertFalse(projection.uploadState.uploading)
        assertEquals(0f, projection.uploadState.progress)
    }

    @Test
    fun `relation change cancels the old upload and the next picker cannot reuse old local files`() {
        val selection = BusinessScheduleAttachmentSelection()
        val oldPicker = selection.tryBegin()!!
        assertNull(selection.tryBegin())
        val oldFile = BusinessAttachmentDraft(
            "00000000-0000-0000-0000-000000000001",
            "A-BCDEFG",
            "a.pdf",
            "C:/a.pdf",
            1,
            "PDF",
        )
        assertTrue(selection.commit(oldPicker, listOf(oldFile)))
        val pendingOldPicker = selection.tryBegin()!!
        var cancellations = 0

        selection.invalidate { cancellations++ }

        assertNull(selection.merge(pendingOldPicker, listOf(oldFile)))
        val newPicker = selection.tryBegin()!!
        assertTrue(newPicker.currentDrafts.isEmpty())
        val newFile = BusinessAttachmentDraft(
            "00000000-0000-0000-0000-000000000002",
            "A-HJKLMN",
            "b.pdf",
            "C:/b.pdf",
            1,
            "PDF",
        )
        assertEquals(listOf(newFile), selection.merge(newPicker, listOf(newFile)))
        assertEquals(1, cancellations)
    }

    @Test
    fun `scope team and type changes cancel upload and invalidate the active selection token`() {
        val base = com.wzx.huitai.desktop.workbench.BusinessScheduleDraft.validForTest().copy(
            scope = BusinessWorkbenchScope.TEAM,
            teamId = "team-1",
            typeId = "type-1",
        )
        val changes = listOf(
            base.copy(scope = BusinessWorkbenchScope.PERSONAL, teamId = null),
            base.copy(teamId = "team-2"),
            base.copy(typeId = "type-2"),
        )

        changes.forEach { changed ->
            val selection = BusinessScheduleAttachmentSelection()
            val active = selection.tryBegin()!!
            var cancellations = 0

            assertTrue(
                invalidateScheduleAttachmentsForBindingChange(
                    current = base,
                    next = changed,
                    selection = selection,
                    cancelUpload = { cancellations++ },
                ),
            )

            assertEquals(1, cancellations)
            assertNull(selection.merge(active, emptyList()))
            assertTrue(selection.tryBegin()!!.currentDrafts.isEmpty())
        }
    }

}
