package com.wzx.huitai.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import com.wzx.huitai.desktop.workbench.BusinessAttachmentUploadState
import com.wzx.huitai.desktop.workbench.BusinessScheduleDraft
import com.wzx.huitai.desktop.workbench.BusinessScheduleFormState
import com.wzx.huitai.desktop.workbench.BusinessScheduleState
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchState
import com.wzx.huitai.desktop.workbench.hasDifferentAttachmentUploadBinding

/**
 * Keeps the Main.kt workbench refresh order explicit and independently testable.
 * The schedule is attached only after the canonical workbench snapshot is available.
 */
internal suspend fun loadBusinessWorkbenchAndSchedule(
    identityEpoch: Long,
    loadWorkbench: suspend (Long) -> Unit,
    loadPage: suspend () -> Unit,
    currentWorkbenchState: () -> BusinessWorkbenchState,
    attachSchedule: (Long, Long, BusinessWorkbenchScope, String?) -> Unit,
    loadSchedule: suspend () -> Unit,
) {
    loadWorkbench(identityEpoch)
    loadPage()
    reloadBusinessScheduleFromWorkbench(
        identityEpoch = identityEpoch,
        currentWorkbenchState = currentWorkbenchState,
        attachSchedule = attachSchedule,
        loadSchedule = loadSchedule,
    )
}

internal suspend fun reloadBusinessScheduleFromWorkbench(
    identityEpoch: Long,
    currentWorkbenchState: () -> BusinessWorkbenchState,
    attachSchedule: (Long, Long, BusinessWorkbenchScope, String?) -> Unit,
    loadSchedule: suspend () -> Unit,
) {
    val loaded = currentWorkbenchState()
    val snapshot = loaded.snapshot ?: return
    attachSchedule(identityEpoch, snapshot.generation, loaded.scope, loaded.teamId)
    loadSchedule()
}

/**
 * One identity boundary owns both the upload lease and the local schedule draft.
 */
internal fun clearBusinessScheduleSession(
    cancelUpload: () -> Unit,
    clearSchedule: () -> Unit,
    clearLocalAttachments: () -> Unit,
) {
    cancelUpload()
    clearSchedule()
    clearLocalAttachments()
}

internal fun transitionBusinessScheduleIdentity(
    previousIdentityEpoch: Long?,
    nextIdentityEpoch: Long,
    cancelUpload: () -> Unit,
    clearSchedule: () -> Unit,
    clearLocalAttachments: () -> Unit,
): Long {
    if (previousIdentityEpoch != null && previousIdentityEpoch != nextIdentityEpoch) {
        clearBusinessScheduleSession(cancelUpload, clearSchedule, clearLocalAttachments)
    }
    return nextIdentityEpoch
}

internal data class BusinessScheduleUiIdentityProjection(
    val scheduleState: BusinessScheduleState,
    val formState: BusinessScheduleFormState,
    val uploadState: BusinessAttachmentUploadState,
)

/**
 * Prevents a new READY identity from rendering the previous identity's local schedule state
 * during the frame before the asynchronous load effect attaches the new canonical snapshot.
 */
internal fun projectBusinessScheduleUiIdentity(
    activeIdentityEpoch: Long?,
    nextIdentityEpoch: Long?,
    scheduleState: BusinessScheduleState,
    formState: BusinessScheduleFormState,
    uploadState: BusinessAttachmentUploadState,
): BusinessScheduleUiIdentityProjection {
    if (nextIdentityEpoch != null && activeIdentityEpoch == nextIdentityEpoch) {
        return BusinessScheduleUiIdentityProjection(scheduleState, formState, uploadState)
    }
    return BusinessScheduleUiIdentityProjection(
        scheduleState = BusinessScheduleState(identityEpoch = nextIdentityEpoch ?: 0),
        formState = BusinessScheduleFormState(),
        uploadState = BusinessAttachmentUploadState(),
    )
}

/**
 * Owns local file paths selected for the current schedule relation. A relation change
 * invalidates both a picker suspended in the native dialog and an upload in flight.
 */
internal class BusinessScheduleAttachmentSelection {
    private var version = 0L
    private var drafts = emptyList<BusinessAttachmentDraft>()
    var picking by mutableStateOf(false)
        private set

    internal data class Request(
        val version: Long,
        val currentDrafts: List<BusinessAttachmentDraft>,
    )

    fun tryBegin(): Request? {
        if (picking) return null
        picking = true
        return Request(version, drafts)
    }

    fun merge(
        request: Request,
        additions: List<BusinessAttachmentDraft>,
    ): List<BusinessAttachmentDraft>? =
        if (picking && request.version == version) request.currentDrafts + additions else null

    fun commit(request: Request, updated: List<BusinessAttachmentDraft>): Boolean {
        if (request.version != version) return false
        drafts = updated
        picking = false
        return true
    }

    fun finish(request: Request) {
        if (request.version == version) picking = false
    }

    fun clear() {
        version++
        drafts = emptyList()
        picking = false
    }

    fun invalidate(cancelUpload: () -> Unit) {
        version++
        drafts = emptyList()
        picking = false
        cancelUpload()
    }
}

internal fun invalidateScheduleAttachmentsForBindingChange(
    current: BusinessScheduleDraft,
    next: BusinessScheduleDraft,
    selection: BusinessScheduleAttachmentSelection,
    cancelUpload: () -> Unit,
): Boolean {
    if (!current.hasDifferentAttachmentUploadBinding(next)) return false
    selection.invalidate(cancelUpload)
    return true
}

internal fun <T> businessWorkbenchBindingChangeCallback(
    selection: BusinessScheduleAttachmentSelection,
    cancelUpload: () -> Unit,
    onBindingChanged: (T) -> Unit,
): (T) -> Unit = { value ->
    selection.invalidate(cancelUpload)
    onBindingChanged(value)
}

internal fun businessScheduleDraftChangeCallback(
    currentDraft: () -> BusinessScheduleDraft,
    selection: BusinessScheduleAttachmentSelection,
    cancelUpload: () -> Unit,
    onDraftChanged: (BusinessScheduleDraft) -> Unit,
): (BusinessScheduleDraft) -> Unit = { nextDraft ->
    invalidateScheduleAttachmentsForBindingChange(
        current = currentDraft(),
        next = nextDraft,
        selection = selection,
        cancelUpload = cancelUpload,
    )
    onDraftChanged(nextDraft)
}
