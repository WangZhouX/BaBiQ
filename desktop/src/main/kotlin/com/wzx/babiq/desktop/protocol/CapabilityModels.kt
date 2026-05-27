package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * capability/status 和 capability/search 共用的能力摘要。
 *
 * @property capabilityId 后端稳定能力 id，用于更新开关和暴露模式。
 * @property type 能力类型，LOCAL_TOOL、MCP_TOOL 或 SKILL。
 * @property namespace 来源命名空间。
 * @property name 能力短名称。
 * @property displayName 设置页展示名称。
 * @property description 能力说明。
 * @property exposureMode 暴露模式，VISIBLE 常驻、DEFERRED 按需、DISABLED 禁用。
 * @property enabled 用户是否启用。
 * @property lastSeenAt 最近一次后端扫描到该能力的时间。
 */
@Serializable
data class CapabilityInfo(
	val capabilityId: String,
	val type: String,
	val namespace: String,
	val name: String,
	val displayName: String,
	val description: String,
	val exposureMode: String,
	val enabled: Boolean,
	val lastSeenAt: String? = null,
)

/** capability/status 响应。 */
@Serializable
data class CapabilityStatusResult(
	val totalCount: Int = 0,
	val enabledCount: Int = 0,
	val visibleCount: Int = 0,
	val deferredCount: Int = 0,
	val disabledCount: Int = 0,
	val capabilities: List<CapabilityInfo> = emptyList(),
)

/** capability/search 响应。 */
@Serializable
data class CapabilitySearchResult(
	val strategy: String,
	val results: List<CapabilityInfo> = emptyList(),
)

/** capability/settings/set 参数。 */
@Serializable
data class CapabilitySettingsSetParams(
	val capabilityId: String,
	val enabled: Boolean? = null,
	val exposureMode: String? = null,
)

/** capability/settings/set 响应。 */
@Serializable
data class CapabilitySettingsSetResult(
	val capability: CapabilityInfo,
)
