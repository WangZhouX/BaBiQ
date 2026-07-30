package com.wzx.huitai.agent.business.workbench

import com.wzx.huitai.agent.business.auth.BusinessNavigationTarget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class BusinessWorkbenchKind { CASE, APPOINTMENT, COUNSELOR_SERVICE, VISIT }
enum class BusinessWorkbenchScope { ALL, PERSONAL, TEAM }
enum class BusinessWorkbenchSectionStatus { OK, EMPTY, ERROR, UNKNOWN }
enum class BusinessWorkbenchSortKind { SHORTCUT, SUMMARY }

data class BusinessWorkbenchNavigation(
    val identityEpoch: Long,
    val generation: Long,
    val items: List<BusinessNavigationTarget>,
) {
    init {
        require(identityEpoch > 0) { "identityEpoch must be positive" }
        require(generation >= 0) { "generation must not be negative" }
        require(items.size <= 100) { "too many navigation targets" }
    }
}

data class BusinessWorkbenchTeamRole(
    val roleCode: String,
    val name: String,
) {
    init {
        require(roleCode.isNotBlank()) { "roleCode must not be blank" }
        require(roleCode.length <= 256) { "roleCode is too long" }
        require(name.length <= 1024) { "role name is too long" }
    }
}

data class BusinessWorkbenchTeamRoles(
    val identityEpoch: Long,
    val generation: Long,
    val items: List<BusinessWorkbenchTeamRole>,
) {
    init {
        require(identityEpoch > 0) { "identityEpoch must be positive" }
        require(generation >= 0) { "generation must not be negative" }
        require(items.size <= 100) { "too many team roles" }
        require(items.map { it.roleCode }.distinct().size == items.size) { "duplicate team role" }
    }
}

data class BusinessWorkbenchSortRequest(
    val kind: BusinessWorkbenchSortKind,
    val ids: List<String>,
    val expectedRevision: Long,
) {
    init {
        require(ids.isNotEmpty()) { "sort ids must not be empty" }
        require(ids.size <= 100) { "too many sort ids" }
        require(ids.all { it.isNotBlank() && it.length <= 256 }) { "invalid sort id" }
        require(ids.distinct().size == ids.size) { "sort ids must be unique" }
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
    }
}

data class BusinessWorkbenchSortMutation(
    val identityEpoch: Long,
    val generation: Long,
    val revision: Long,
    val refreshRequired: Boolean,
    val canonicalIds: List<String>?,
) {
    init {
        require(identityEpoch > 0) { "identityEpoch must be positive" }
        require(generation >= 0) { "generation must not be negative" }
        require(revision >= 0) { "revision must not be negative" }
        require(refreshRequired || !canonicalIds.isNullOrEmpty()) {
            "canonical sort ids are required when refresh is not required"
        }
        canonicalIds?.let { ids ->
            require(ids.isNotEmpty()) { "canonical sort ids must not be empty" }
            require(ids.all { it.isNotBlank() && it.length <= 256 }) { "invalid canonical sort id" }
            require(ids.distinct().size == ids.size) { "canonical sort ids must be unique" }
        }
    }
}

data class BusinessWorkbenchPageRequest(
    val kind: BusinessWorkbenchKind,
    val scope: BusinessWorkbenchScope = BusinessWorkbenchScope.ALL,
    val teamId: String? = null,
    val roleCode: String? = null,
    val pageNo: Int = 1,
    val pageSize: Int = 20,
    val filters: Map<String, Any?> = emptyMap(),
)

data class BusinessWorkbenchSection(
    val status: BusinessWorkbenchSectionStatus = BusinessWorkbenchSectionStatus.UNKNOWN,
    val data: JsonElement? = null,
) {
    override fun toString(): String = "BusinessWorkbenchSection(status=$status, data=${if (data == null) "null" else "[REDACTED]"})"
}

data class BusinessWorkbenchSnapshot(
    val identityEpoch: Long,
    val generation: Long,
    val notices: BusinessWorkbenchSection = BusinessWorkbenchSection(),
    val shortcuts: BusinessWorkbenchSection = BusinessWorkbenchSection(),
    val summary: BusinessWorkbenchSection = BusinessWorkbenchSection(),
    val profile: BusinessWorkbenchSection = BusinessWorkbenchSection(),
    val teams: BusinessWorkbenchSection = BusinessWorkbenchSection(),
    val schedule: BusinessWorkbenchSection = BusinessWorkbenchSection(),
    val issues: List<String> = emptyList(),
) {
    init {
        require(identityEpoch > 0) { "identityEpoch must be positive" }
        require(generation >= 0) { "generation must not be negative" }
    }

    override fun toString(): String =
        "BusinessWorkbenchSnapshot(identityEpoch=$identityEpoch, generation=$generation, sections=[notices, shortcuts, summary, profile, teams, schedule], issues=$issues)"
}

data class BusinessWorkbenchPageItem(
    val id: String,
    val applicationNumber: String? = null,
    val categoriesName: String? = null,
    val scheduleName: String? = null,
    val title: String? = null,
    val values: JsonObject = JsonObject(emptyMap()),
) {
    override fun toString(): String = "BusinessWorkbenchPageItem(id=[REDACTED], title=[REDACTED], values=[REDACTED])"
}

data class BusinessWorkbenchPage(
    val identityEpoch: Long,
    val generation: Long,
    val total: Long,
    val pageNo: Int,
    val pageSize: Int,
    val items: List<BusinessWorkbenchPageItem>,
) {
    init {
        require(identityEpoch > 0) { "identityEpoch must be positive" }
        require(generation >= 0) { "generation must not be negative" }
        require(total >= 0) { "total must not be negative" }
        require(pageNo > 0) { "pageNo must be positive" }
        require(pageSize > 0) { "pageSize must be positive" }
    }
}
