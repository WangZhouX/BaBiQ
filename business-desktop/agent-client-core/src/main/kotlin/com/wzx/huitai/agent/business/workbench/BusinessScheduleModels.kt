package com.wzx.huitai.agent.business.workbench

data class BusinessScheduleQuery(
    val date: String,
    val scope: BusinessWorkbenchScope,
    val teamId: String? = null,
    val onlyMine: Boolean = false,
    val typeId: String? = null,
)

data class BusinessScheduleMonthEntry(
    val date: String,
    val count: Int,
)

data class BusinessScheduleMonthData(
    val identityEpoch: Long,
    val generation: Long,
    val days: List<BusinessScheduleMonthEntry>,
) {
    override fun toString(): String =
        "BusinessScheduleMonthData(identityEpoch=$identityEpoch, generation=$generation, days=${days.size})"
}

data class BusinessScheduleDayItem(
    val id: String,
    val title: String,
    val at: String,
    val completed: Boolean,
    val typeTitle: String? = null,
    val color: String? = null,
    val priority: Int? = null,
    val repetition: Int? = null,
    val expiredDays: Int? = null,
)

data class BusinessScheduleDayGroup(
    val time: String,
    val allDay: Boolean,
    val items: List<BusinessScheduleDayItem>,
)

data class BusinessScheduleDayData(
    val identityEpoch: Long,
    val generation: Long,
    val groups: List<BusinessScheduleDayGroup>,
) {
    override fun toString(): String =
        "BusinessScheduleDayData(identityEpoch=$identityEpoch, generation=$generation, groups=${groups.size})"
}

data class BusinessScheduleCompletion(
    val identityEpoch: Long,
    val generation: Long,
    val completed: Boolean,
    val refreshRequired: Boolean,
    val revision: Long,
)

data class BusinessScheduleOption(
    val id: String,
    val name: String,
    val values: Map<String, String> = emptyMap(),
)

data class BusinessScheduleForm(
    val identityEpoch: Long,
    val generation: Long,
    val revision: Long,
    val types: List<BusinessScheduleOption>,
    val members: List<BusinessScheduleOption>,
)

data class BusinessScheduleRelationOptions(
    val identityEpoch: Long,
    val generation: Long,
    val revision: Long,
    val relationType: String,
    val items: List<BusinessScheduleOption>,
)

data class BusinessScheduleRelation(
    val relationType: String,
    val id: String,
    val name: String? = null,
    val parentId: String? = null,
)

data class BusinessScheduleCreateRequest(
    val clientOperationId: String,
    val scope: BusinessWorkbenchScope,
    val teamId: String?,
    val assigneeUserId: String?,
    val title: String,
    val typeId: String,
    val at: String,
    val allDay: Boolean,
    val priority: Int,
    val description: String?,
    val reminderMinutes: List<Int>,
    val relations: List<BusinessScheduleRelation>,
    val attachmentBatchId: String?,
    val attachmentParentResourceId: String?,
    val attachmentParentRelationType: String?,
    val formRevision: Long,
    val repetition: Int,
)

data class BusinessScheduleMutation(
    val identityEpoch: Long,
    val generation: Long,
    val revision: Long,
    val refreshRequired: Boolean,
)

interface BusinessScheduleClient {
    suspend fun month(query: BusinessScheduleQuery): BusinessScheduleMonthData
    suspend fun day(query: BusinessScheduleQuery): BusinessScheduleDayData
    suspend fun setCompletion(id: String, completed: Boolean): BusinessScheduleCompletion
    suspend fun form(scope: BusinessWorkbenchScope, teamId: String?): BusinessScheduleForm
    suspend fun relationOptions(
        type: String,
        keyword: String?,
        teamId: String?,
        parentId: String?,
    ): BusinessScheduleRelationOptions

    suspend fun serviceProjects(
        recordId: String,
        keyword: String?,
        teamId: String? = null,
    ): BusinessScheduleRelationOptions
    suspend fun create(request: BusinessScheduleCreateRequest): BusinessScheduleMutation
}

data class BusinessAttachmentFile(
    val fileName: String,
    val sizeBytes: Long,
    val mediaType: String,
    val sha256: String? = null,
)

data class BusinessAttachmentPrepareRequest(
    val operation: String,
    val clientOperationId: String,
    val scope: BusinessWorkbenchScope,
    val teamId: String?,
    val typeId: String,
    val parentRelationType: String,
    val parentResourceId: String,
    val parentRecordId: String? = null,
    val formRevision: Long,
    val files: List<BusinessAttachmentFile> = emptyList(),
) {
    companion object {
        fun validForTest(): BusinessAttachmentPrepareRequest = BusinessAttachmentPrepareRequest(
            operation = "SCHEDULE_CREATE",
            clientOperationId = "operation-1",
            scope = BusinessWorkbenchScope.PERSONAL,
            teamId = null,
            typeId = "type-1",
            parentRelationType = "CASE",
            parentResourceId = "case-1",
            formRevision = 1,
        )
    }
}

data class BusinessAttachmentPrepared(
    val attachmentBatchId: String,
    val ticket: String,
    val expiresAt: String,
    val identityEpoch: Long,
    val generation: Long,
) {
    override fun toString(): String =
        "BusinessAttachmentPrepared(attachmentBatchId=[REDACTED], ticket=[REDACTED], " +
            "expiresAt=$expiresAt, identityEpoch=$identityEpoch, generation=$generation)"
}

fun interface BusinessAttachmentPrepareClient {
    suspend fun prepareAttachment(request: BusinessAttachmentPrepareRequest): BusinessAttachmentPrepared
}
