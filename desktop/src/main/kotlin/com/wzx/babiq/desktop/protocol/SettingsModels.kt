package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * 后端 `settings/get` 和 `settings/update` 返回的本地设置快照。
 *
 * @property activeProviderId 下一轮 turn 默认使用的 Provider；为空表示后端还没有保存显式选择。
 * @property sandboxMode 下一轮 turn 使用的沙箱模式，例如 READ_ONLY、WORKSPACE_WRITE、DANGER_FULL_ACCESS。
 * @property approvalPolicy 下一轮 turn 使用的审批策略，例如 ON_REQUEST、NEVER。
 * @property defaultCwd 新建会话默认工作目录；为空时桌面端继续使用当前选择的工作区。
 */
@Serializable
data class AppSettingsResult(
	val activeProviderId: String? = null,
	val sandboxMode: String,
	val approvalPolicy: String,
	val defaultCwd: String? = null,
)

/**
 * 桌面端修改本地设置时发送给 `settings/update` 的局部更新。
 *
 * <p>所有字段都允许为空，表示“不修改该字段”。协议 JSON 使用 `explicitNulls=false`，
 * 因此空字段不会发给后端，避免误把现有设置清空。</p>
 *
 * @property activeProviderId 要设为默认的 Provider。
 * @property sandboxMode 要保存的沙箱模式。
 * @property approvalPolicy 要保存的审批策略。
 * @property defaultCwd 要保存的默认工作目录。
 */
@Serializable
data class SettingsUpdateParams(
	val activeProviderId: String? = null,
	val sandboxMode: String? = null,
	val approvalPolicy: String? = null,
	val defaultCwd: String? = null,
)

/**
 * `approval/policy/set` 的响应模型。
 *
 * @property approvalPolicy 后端最终保存的审批策略。
 */
@Serializable
data class ApprovalPolicyResult(
	val approvalPolicy: String,
)

/**
 * 修改审批策略时的请求参数。
 *
 * @property approvalPolicy 目标审批策略枚举名。
 */
@Serializable
data class ApprovalPolicySetParams(
	val approvalPolicy: String,
)
