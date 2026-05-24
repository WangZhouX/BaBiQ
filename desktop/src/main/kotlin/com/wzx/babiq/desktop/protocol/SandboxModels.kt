package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * 后端 `sandbox/policy` 返回的权限策略。
 *
 * @property mode 后端真实沙箱枚举名，例如 DANGER_FULL_ACCESS。
 * @property label 给用户看的权限文案，例如“完全访问权限”。
 */
@Serializable
data class SandboxPolicyResult(
	val mode: String,
	val label: String,
)
