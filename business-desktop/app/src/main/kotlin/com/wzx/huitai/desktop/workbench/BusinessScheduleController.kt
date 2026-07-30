package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.agent.business.workbench.BusinessScheduleClient
import com.wzx.huitai.agent.business.workbench.BusinessScheduleCreateRequest
import com.wzx.huitai.agent.business.workbench.BusinessScheduleRelation
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BusinessScheduleViewMode { MONTH, WEEK }

enum class BusinessScheduleRelationType { CUSTOMER, CASE, VISIT, SERVICE }

data class BusinessScheduleItem(
    val id: String,
    val title: String,
    val at: String,
    val completed: Boolean,
    val groupTime: String = "",
    val allDay: Boolean = false,
    val typeTitle: String? = null,
    val color: String? = null,
    val priority: Int? = null,
    val repetition: Int? = null,
    val expiredDays: Int? = null,
)

data class BusinessScheduleState(
    val identityEpoch: Long = 0,
    val generation: Long = 0,
    val visibleMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val viewMode: BusinessScheduleViewMode = BusinessScheduleViewMode.MONTH,
    val scope: BusinessWorkbenchScope = BusinessWorkbenchScope.PERSONAL,
    val teamId: String? = null,
    val onlyMine: Boolean = false,
    val eventDates: Set<LocalDate> = emptySet(),
    val items: List<BusinessScheduleItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class BusinessScheduleFormOption(
    val id: String,
    val name: String,
)

data class BusinessScheduleDraft(
    val clientOperationId: String = UUID.randomUUID().toString(),
    val scope: BusinessWorkbenchScope,
    val teamId: String?,
    val assigneeUserId: String? = null,
    val title: String = "",
    val typeId: String = "",
    val at: String = "",
    val allDay: Boolean = false,
    val priority: Int = 2,
    val description: String = "",
    val reminderMinutes: List<Int> = emptyList(),
    val repeatRule: String = "NONE",
    val relations: List<BusinessScheduleRelation> = emptyList(),
    val attachmentBatchId: String? = null,
    val attachmentParentResourceId: String? = null,
    val attachmentParentRelationType: String? = null,
) {
    companion object {
        fun validForTest(attachmentBatchId: String? = null): BusinessScheduleDraft =
            BusinessScheduleDraft(
                clientOperationId = "operation-test",
                scope = BusinessWorkbenchScope.PERSONAL,
                teamId = null,
                title = "客户会议",
                typeId = "type-1",
                at = "2026-07-29 10:00:00",
                attachmentBatchId = attachmentBatchId,
                attachmentParentResourceId = attachmentBatchId?.let { "case-1" },
                attachmentParentRelationType = attachmentBatchId?.let { "CASE" },
            )
    }
}

data class BusinessScheduleFormState(
    val visible: Boolean = false,
    val canAssignOthers: Boolean = false,
    val draft: BusinessScheduleDraft = BusinessScheduleDraft(
        scope = BusinessWorkbenchScope.PERSONAL,
        teamId = null,
    ),
    val revision: Long = 0,
    val types: List<BusinessScheduleFormOption> = emptyList(),
    val members: List<BusinessScheduleFormOption> = emptyList(),
    val relationTypes: Set<BusinessScheduleRelationType> = BusinessScheduleRelationType.entries.toSet(),
    val selectedRelationType: BusinessScheduleRelationType? = null,
    val selectedServiceRecord: BusinessScheduleFormOption? = null,
    val relationOptions: List<BusinessScheduleFormOption> = emptyList(),
    val attachmentNames: List<String> = emptyList(),
    val submitting: Boolean = false,
    val error: String? = null,
)

class BusinessScheduleController(
    private val client: BusinessScheduleClient,
    private val onIdentityChanged: (Long, Long) -> Unit = { _, _ -> },
) {
    private val mutableState = MutableStateFlow(BusinessScheduleState())
    val state: StateFlow<BusinessScheduleState> = mutableState.asStateFlow()

    private val mutableFormState = MutableStateFlow(BusinessScheduleFormState())
    val formState: StateFlow<BusinessScheduleFormState> = mutableFormState.asStateFlow()

    private var attachmentSerial = 0L
    private var requestSerial = 0L
    private var activeLoad: ScheduleRequestToken? = null
    private val activeCompletions = mutableMapOf<String, ScheduleRequestToken>()
    private var activeForm: ScheduleRequestToken? = null
    private var activeRelation: ScheduleRequestToken? = null
    private var activeCreate: ScheduleRequestToken? = null

    fun attach(
        identityEpoch: Long,
        generation: Long,
        scope: BusinessWorkbenchScope,
        teamId: String?,
    ) {
        invalidateRequests()
        attachmentSerial++
        onIdentityChanged(identityEpoch, generation)
        mutableState.value = BusinessScheduleState(
            identityEpoch = identityEpoch,
            generation = generation,
            scope = scope,
            teamId = teamId,
        )
        mutableFormState.value = BusinessScheduleFormState(
            draft = BusinessScheduleDraft(scope = scope, teamId = teamId),
        )
    }

    fun clear() {
        invalidateRequests()
        attachmentSerial++
        mutableState.value = BusinessScheduleState()
        mutableFormState.value = BusinessScheduleFormState()
    }

    suspend fun load(date: LocalDate = mutableState.value.selectedDate) {
        val request = nextRequest().also { activeLoad = it }
        val viewMode = mutableState.value.viewMode
        mutableState.value = mutableState.value.copy(
            selectedDate = date,
            visibleMonth = YearMonth.from(date),
            loading = true,
            error = null,
        )
        try {
            val current = mutableState.value
            val months = requestedMonths(date, viewMode).map { month ->
                client.month(current.query(month.toString()))
            }
            val day = client.day(current.query(date.toString()))
            if (months.any { month ->
                    !matches(request, activeLoad, month.identityEpoch, month.generation)
                }
                || !matches(request, activeLoad, day.identityEpoch, day.generation)
            ) return
            mutableState.value = mutableState.value.copy(
                eventDates = months.asSequence()
                    .flatMap { it.days.asSequence() }
                    .filter { it.count > 0 }
                    .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                    .toSet(),
                items = day.groups.flatMap { group ->
                    group.items.map { item ->
                        BusinessScheduleItem(
                            id = item.id,
                            title = item.title,
                            at = item.at,
                            completed = item.completed,
                            groupTime = group.time,
                            allDay = group.allDay,
                            typeTitle = item.typeTitle,
                            color = item.color,
                            priority = item.priority,
                            repetition = item.repetition,
                            expiredDays = item.expiredDays,
                        )
                    }
                },
                loading = false,
            )
        } catch (failure: Throwable) {
            if (matches(request, activeLoad)) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = failure.message ?: failure::class.simpleName,
                )
            }
        }
    }

    suspend fun selectDate(date: LocalDate) = load(date)

    suspend fun setViewMode(mode: BusinessScheduleViewMode) {
        if (mutableState.value.viewMode == mode) return
        mutableState.value = mutableState.value.copy(viewMode = mode)
        load(mutableState.value.selectedDate)
    }

    suspend fun next() {
        val current = mutableState.value
        val next = if (current.viewMode == BusinessScheduleViewMode.WEEK) {
            current.selectedDate.plusWeeks(1)
        } else {
            current.selectedDate.plusMonths(1)
        }
        load(next)
    }

    suspend fun previous() {
        val current = mutableState.value
        val previous = if (current.viewMode == BusinessScheduleViewMode.WEEK) {
            current.selectedDate.minusWeeks(1)
        } else {
            current.selectedDate.minusMonths(1)
        }
        load(previous)
    }

    suspend fun today(today: LocalDate = LocalDate.now()) = load(today)

    suspend fun setOnlyMine(onlyMine: Boolean) {
        mutableState.value = mutableState.value.copy(onlyMine = onlyMine)
        load(mutableState.value.selectedDate)
    }

    suspend fun setCompleted(id: String, completed: Boolean) {
        val before = mutableState.value
        val request = nextRequest().also { activeCompletions[id] = it }
        val previousCompleted = before.items.firstOrNull { it.id == id }?.completed
        mutableState.value = before.copy(
            items = before.items.map { if (it.id == id) it.copy(completed = completed) else it },
            error = null,
        )
        try {
            val result = client.setCompletion(id, completed)
            if (!matches(request, activeCompletions[id], result.identityEpoch, result.generation)) return
            if (activeCompletions[id] == request) activeCompletions.remove(id)
            if (result.refreshRequired) load(mutableState.value.selectedDate)
        } catch (failure: Throwable) {
            if (matches(request, activeCompletions[id])) {
                if (activeCompletions[id] == request) activeCompletions.remove(id)
                mutableState.value = mutableState.value.copy(
                    items = mutableState.value.items.map { item ->
                        if (item.id == id && previousCompleted != null) {
                            item.copy(completed = previousCompleted)
                        } else {
                            item
                        }
                    },
                    error = failure.message ?: "日程更新失败",
                )
            }
        }
    }

    suspend fun openCreate() {
        val current = mutableState.value
        activeCreate = null
        val request = nextRequest().also { activeForm = it }
        try {
            val form = client.form(current.scope, current.teamId)
            if (!matches(request, activeForm, form.identityEpoch, form.generation)) return
            val members = form.members.map { BusinessScheduleFormOption(it.id, it.name) }
            mutableFormState.value = BusinessScheduleFormState(
                visible = true,
                canAssignOthers = members.size > 1,
                draft = BusinessScheduleDraft(
                    scope = current.scope,
                    teamId = current.teamId,
                    assigneeUserId = members.singleOrNull()?.id,
                    at = "${current.selectedDate} 09:00:00",
                ),
                revision = form.revision,
                types = form.types.map { BusinessScheduleFormOption(it.id, it.name) },
                members = members,
            )
        } catch (failure: Throwable) {
            if (matches(request, activeForm)) {
                mutableFormState.value = mutableFormState.value.copy(
                    visible = true,
                    error = failure.message ?: "日程表单加载失败",
                )
            }
        }
    }

    fun updateDraft(draft: BusinessScheduleDraft) {
        val current = mutableFormState.value
        val bindingChanged = current.draft.hasDifferentAttachmentUploadBinding(draft)
        if (bindingChanged) attachmentSerial++
        mutableFormState.value = current.copy(
            draft = if (bindingChanged) {
                draft.copy(
                    attachmentBatchId = null,
                    attachmentParentResourceId = null,
                    attachmentParentRelationType = null,
                )
            } else {
                draft
            },
            attachmentNames = if (bindingChanged) emptyList() else current.attachmentNames,
            error = null,
        )
    }

    suspend fun loadRelationOptions(type: BusinessScheduleRelationType, keyword: String? = null) {
        val current = mutableState.value
        val request = nextRequest().also { activeRelation = it }
        attachmentSerial++
        mutableFormState.value = mutableFormState.value.copy(
            selectedRelationType = type,
            selectedServiceRecord = null,
            relationOptions = emptyList(),
            draft = mutableFormState.value.draft.copy(relations = emptyList()),
            error = null,
        )
        try {
            val result = client.relationOptions(type.name, keyword, current.teamId, null)
            if (!matches(request, activeRelation, result.identityEpoch, result.generation)) return
            mutableFormState.value = mutableFormState.value.copy(
                relationOptions = result.items.map { BusinessScheduleFormOption(it.id, it.name) },
            )
        } catch (failure: Throwable) {
            if (matches(request, activeRelation)) {
                mutableFormState.value = mutableFormState.value.copy(
                    error = failure.message ?: "关联选项加载失败",
                )
            }
        }
    }

    suspend fun selectRelationOption(
        type: BusinessScheduleRelationType,
        option: BusinessScheduleFormOption,
        keyword: String? = null,
    ) {
        val form = mutableFormState.value
        if (form.selectedRelationType != type || option !in form.relationOptions) return
        if (type == BusinessScheduleRelationType.SERVICE && form.selectedServiceRecord == null) {
            val request = nextRequest().also { activeRelation = it }
            mutableFormState.value = form.copy(
                selectedServiceRecord = option,
                relationOptions = emptyList(),
                draft = form.draft.copy(relations = emptyList()),
                error = null,
            )
            try {
                val result = client.serviceProjects(option.id, keyword, form.draft.teamId)
                if (!matches(request, activeRelation, result.identityEpoch, result.generation)) return
                mutableFormState.value = mutableFormState.value.copy(
                    relationOptions = result.items.map { BusinessScheduleFormOption(it.id, it.name) },
                )
            } catch (failure: Throwable) {
                if (matches(request, activeRelation)) {
                    mutableFormState.value = mutableFormState.value.copy(
                        error = failure.message ?: "服务项目加载失败",
                    )
                }
            }
            return
        }
        val parentId = form.selectedServiceRecord?.id.takeIf {
            type == BusinessScheduleRelationType.SERVICE
        }
        mutableFormState.value = form.copy(
            draft = form.draft.copy(
                relations = listOf(BusinessScheduleRelation(type.name, option.id, option.name, parentId)),
                attachmentBatchId = null,
                attachmentParentResourceId = null,
                attachmentParentRelationType = null,
            ),
            attachmentNames = emptyList(),
            error = null,
        )
    }

    internal fun beginAttachmentUpload(): BusinessScheduleAttachmentRequestToken {
        val state = mutableState.value
        val draft = mutableFormState.value.draft
        val parent = draft.currentAttachmentParent()
        return BusinessScheduleAttachmentRequestToken(
            requestId = ++attachmentSerial,
            identityEpoch = state.identityEpoch,
            generation = state.generation,
            clientOperationId = draft.clientOperationId,
            parentRelationType = parent?.relationType,
            parentResourceId = parent?.id,
            parentRecordId = parent?.parentId,
        )
    }

    internal fun completeAttachmentUpload(
        request: BusinessScheduleAttachmentRequestToken,
        names: List<String>,
        attachmentBatchId: String,
        attachmentParentResourceId: String,
        attachmentParentRelationType: String,
    ): Boolean {
        if (!matchesAttachment(request)) return false
        val current = mutableFormState.value
        val draft = current.draft
        val parent = draft.currentAttachmentParent()
        if (draft.clientOperationId != request.clientOperationId ||
            parent?.relationType != request.parentRelationType ||
            parent?.id != request.parentResourceId ||
            parent?.parentId != request.parentRecordId ||
            attachmentParentRelationType != request.parentRelationType ||
            attachmentParentResourceId != request.parentResourceId
        ) {
            return false
        }
        mutableFormState.value = mutableFormState.value.copy(
            attachmentNames = names,
            draft = draft.copy(
                attachmentBatchId = attachmentBatchId,
                attachmentParentResourceId = attachmentParentResourceId,
                attachmentParentRelationType = attachmentParentRelationType,
            ),
            error = null,
        )
        return true
    }

    internal fun failAttachmentUpload(
        request: BusinessScheduleAttachmentRequestToken,
        message: String,
    ) {
        if (!matchesAttachment(request)) return
        mutableFormState.value = mutableFormState.value.copy(error = message)
    }

    fun setAttachmentNames(names: List<String>) {
        attachmentSerial++
        mutableFormState.value = mutableFormState.value.copy(attachmentNames = names)
    }

    fun setFormError(message: String?) {
        mutableFormState.value = mutableFormState.value.copy(error = message)
    }

    fun discardAttachments() {
        attachmentSerial++
        mutableFormState.value = mutableFormState.value.copy(attachmentNames = emptyList())
    }

    suspend fun submit(draft: BusinessScheduleDraft = mutableFormState.value.draft) {
        if (mutableFormState.value.submitting) return
        val form = mutableFormState.value
        val validationError = validateDraft(draft, form)
        if (validationError != null) {
            mutableFormState.value = form.copy(
                submitting = false,
                error = validationError,
                draft = draft,
            )
            return
        }
        val request = nextRequest().also { activeCreate = it }
        mutableFormState.value = form.copy(submitting = true, error = null, draft = draft)
        try {
            val result = client.create(
                BusinessScheduleCreateRequest(
                    clientOperationId = draft.clientOperationId,
                    scope = draft.scope,
                    teamId = draft.teamId,
                    assigneeUserId = draft.assigneeUserId,
                    title = draft.title,
                    typeId = draft.typeId,
                    at = if (draft.allDay) {
                        LocalDateTime.parse(draft.at, SCHEDULE_DATE_TIME)
                            .toLocalDate()
                            .atStartOfDay()
                            .format(SCHEDULE_DATE_TIME)
                    } else {
                        draft.at
                    },
                    allDay = draft.allDay,
                    priority = draft.priority,
                    description = draft.description.takeIf(String::isNotBlank),
                    reminderMinutes = draft.reminderMinutes,
                    relations = draft.relations,
                    attachmentBatchId = draft.attachmentBatchId,
                    attachmentParentResourceId = draft.attachmentParentResourceId,
                    attachmentParentRelationType = draft.attachmentParentRelationType,
                    formRevision = form.revision,
                    repetition = repetition(draft),
                ),
            )
            if (!matches(request, activeCreate, result.identityEpoch, result.generation)) return
            mutableFormState.value = mutableFormState.value.copy(visible = false, submitting = false)
            if (result.refreshRequired) load(mutableState.value.selectedDate)
        } catch (failure: Throwable) {
            if (matches(request, activeCreate)) {
                mutableFormState.value = mutableFormState.value.copy(
                    submitting = false,
                    error = failure.message ?: "日程创建失败",
                )
            }
        }
    }

    fun dismissCreate() {
        activeCreate = null
        activeForm = null
        activeRelation = null
        mutableFormState.value = mutableFormState.value.copy(visible = false, error = null)
    }

    private fun BusinessScheduleState.query(date: String) =
        com.wzx.huitai.agent.business.workbench.BusinessScheduleQuery(
            date = date,
            scope = scope,
            teamId = teamId,
            onlyMine = onlyMine,
        )

    private fun requestedMonths(
        date: LocalDate,
        viewMode: BusinessScheduleViewMode,
    ): List<YearMonth> {
        if (viewMode == BusinessScheduleViewMode.MONTH) return listOf(YearMonth.from(date))
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        return listOf(YearMonth.from(monday), YearMonth.from(monday.plusDays(6))).distinct()
    }

    private fun nextRequest(): ScheduleRequestToken {
        val state = mutableState.value
        return ScheduleRequestToken(++requestSerial, state.identityEpoch, state.generation)
    }

    private fun matches(
        request: ScheduleRequestToken,
        active: ScheduleRequestToken?,
        epoch: Long = request.identityEpoch,
        generation: Long = request.generation,
    ): Boolean {
        val state = mutableState.value
        return active == request &&
            state.identityEpoch == request.identityEpoch &&
            state.generation == request.generation &&
            epoch == request.identityEpoch &&
            generation == request.generation
    }

    private fun invalidateRequests() {
        requestSerial++
        activeLoad = null
        activeCompletions.clear()
        activeForm = null
        activeRelation = null
        activeCreate = null
    }

    private fun matchesAttachment(request: BusinessScheduleAttachmentRequestToken): Boolean {
        val state = mutableState.value
        return attachmentSerial == request.requestId &&
            state.identityEpoch == request.identityEpoch &&
            state.generation == request.generation
    }

    private fun repetition(draft: BusinessScheduleDraft): Int {
        if (draft.relations.any {
                it.relationType == BusinessScheduleRelationType.VISIT.name ||
                    it.relationType == BusinessScheduleRelationType.SERVICE.name
            }
        ) {
            return 0
        }
        return when (draft.repeatRule.uppercase()) {
        "DAILY" -> 1
        "WEEKLY" -> 2
        "MONTHLY" -> 3
        "YEARLY" -> 4
        else -> 0
        }
    }

    private fun validateDraft(
        draft: BusinessScheduleDraft,
        form: BusinessScheduleFormState,
    ): String? {
        if (draft.title.isBlank() || draft.title.length > 50) return "请输入不超过 50 字的日程标题"
        if (draft.typeId.isBlank() || form.types.isNotEmpty() && form.types.none { it.id == draft.typeId }) {
            return "请选择有效的日程类型"
        }
        if (draft.scope == BusinessWorkbenchScope.TEAM && draft.assigneeUserId.isNullOrBlank()) {
            return "请选择指派成员"
        }
        if (draft.priority !in 1..4) return "请选择有效的优先级"
        if (runCatching { LocalDateTime.parse(draft.at, SCHEDULE_DATE_TIME) }.isFailure) {
            return "请选择有效的日程时间"
        }
        if (draft.description.length > 200) return "日程描述不能超过 200 字"
        if (draft.reminderMinutes.size > 20 ||
            draft.reminderMinutes.any {
                it == 0 || it < 0 && (!draft.allDay || it !in ALL_DAY_NEGATIVE_REMINDERS)
            } ||
            draft.reminderMinutes.distinct().size != draft.reminderMinutes.size
        ) {
            return "请选择有效且不重复的提醒时间"
        }
        return null
    }

}

private data class ScheduleRequestToken(
    val requestId: Long,
    val identityEpoch: Long,
    val generation: Long,
)

internal data class BusinessScheduleAttachmentRequestToken(
    val requestId: Long,
    val identityEpoch: Long,
    val generation: Long,
    val clientOperationId: String,
    val parentRelationType: String?,
    val parentResourceId: String?,
    val parentRecordId: String?,
)

private val SCHEDULE_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val ALL_DAY_NEGATIVE_REMINDERS = setOf(-540, -600, -840)

internal fun BusinessScheduleDraft.currentAttachmentParent(): BusinessScheduleRelation? =
    relations.singleOrNull()

internal fun BusinessScheduleDraft.hasDifferentAttachmentUploadBinding(
    other: BusinessScheduleDraft,
): Boolean =
    scope != other.scope ||
        teamId != other.teamId ||
        typeId != other.typeId
