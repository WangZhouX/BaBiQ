package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * Provider 新增/编辑表单提交给后端的参数。
 *
 * @property providerId 本地唯一 Provider 标识，由用户输入或 UI 自动生成。
 * @property displayName 展示名称，设置页列表和模型下拉会显示它。
 * @property type Provider 类型，目前主要是 OPENAI_COMPATIBLE 和 DASHSCOPE。
 * @property baseUrl OpenAI 兼容接口地址；DashScope 这类官方 Provider 可以为空字符串。
 * @property model 默认模型名称，下一轮 turn 使用该 Provider 时传给后端模型工厂。
 * @property apiKey 明文 API Key，只在保存请求里出现；后端会立刻写入 SecretStore，不会回显。
 * @property contextWindow 上下文窗口大小，0 表示使用后端默认值或未知。
 * @property enabled 是否启用该 Provider；删除在 P2-3 中表现为禁用。
 */
@Serializable
data class ProviderSaveParams(
	val providerId: String,
	val displayName: String,
	val type: String,
	val authMode: String = "api_key",
	val baseUrl: String,
	val model: String,
	val apiKey: String? = null,
	val contextWindow: Int = 0,
	val enabled: Boolean = true,
)

/**
 * Provider 新增/编辑后的非敏感响应。
 *
 * @property id Provider 标识。
 * @property label UI 展示名称，兼容 P1 的 ProviderInfo 字段。
 * @property displayName 设置页展示名称。
 * @property type Provider 类型。
 * @property baseUrl API Base URL；后端可能对不需要 baseUrl 的 Provider 返回空字符串。
 * @property model 默认模型。
 * @property contextWindow 上下文窗口大小。
 * @property enabled 是否启用。
 * @property hasApiKey 后端是否已保存 API Key。
 * @property active 是否为当前默认 Provider。
 * @property apiKey 永远应为空；保留字段用于测试确认后端不回显明文。
 * @property models Provider 下可选模型列表，模型下拉沿用同一结构。
 */
@Serializable
data class ProviderMutationResult(
	val id: String,
	val label: String,
	val displayName: String = label,
	val type: String? = null,
	val authMode: String = "api_key",
	val baseUrl: String? = null,
	val model: String? = null,
	val contextWindow: Int = 0,
	val enabled: Boolean = true,
	val hasApiKey: Boolean = false,
	val active: Boolean = false,
	val apiKey: String? = null,
	val models: List<ModelInfo> = emptyList(),
)

/**
 * 删除 Provider 的响应。
 *
 * @property ok 后端是否接受删除/禁用操作。
 * @property providerId 被删除或禁用的 Provider。
 * @property archived 兼容会话归档命名；Provider 删除时通常为 false。
 */
@Serializable
data class ProviderDeleteResult(
	val ok: Boolean = true,
	val providerId: String,
	val archived: Boolean = false,
)

/**
 * 删除 Provider 时发给后端的参数。
 *
 * @property providerId 要禁用的 Provider 标识。
 */
@Serializable
data class ProviderDeleteParams(
	val providerId: String,
)

/**
 * Provider 轻量测试连接结果。
 *
 * @property ok true 表示后端可以构造对应 ChatModel。
 * @property providerId 被测试的 Provider。
 * @property message 展示给用户的测试结果。
 */
@Serializable
data class ProviderTestResult(
	val ok: Boolean,
	val providerId: String,
	val message: String,
)

/**
 * 测试 Provider 时的请求参数。
 *
 * @property providerId 要测试的 Provider 标识。
 */
@Serializable
data class ProviderTestParams(
	val providerId: String,
)

/**
 * Claude OAuth CLI 登录状态。
 */
@Serializable
data class ProviderOAuthStatusResult(
	val providerType: String,
	val authMode: String,
	val cliInstalled: Boolean,
	val loggedIn: Boolean,
	val message: String,
)

/**
 * 启动 Claude CLI OAuth 登录流程的响应。
 */
@Serializable
data class ProviderOAuthLoginResult(
	val ok: Boolean,
	val pid: Long? = null,
	val message: String,
)

/**
 * 修改沙箱策略时的请求参数。
 *
 * @property mode 目标沙箱模式枚举名。
 */
@Serializable
data class SandboxPolicySetParams(
	val mode: String,
)
