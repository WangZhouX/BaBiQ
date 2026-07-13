package com.wzx.huitai.action.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 动作可重放策略。 */
@Serializable
enum class ActionReplayPolicy {
    @SerialName("safe")
    SAFE,

    @SerialName("idempotency_key_required")
    IDEMPOTENCY_KEY_REQUIRED,

    @SerialName("never")
    NEVER,
}

/** 结果不确定后的对账策略。 */
@Serializable
enum class ReconciliationPolicy {
    @SerialName("none")
    NONE,

    @SerialName("query_remote")
    QUERY_REMOTE,

    @SerialName("manual")
    MANUAL,
}

/**
 * 动作作用目标。
 *
 * @param pageType 目标页面类型。
 * @param operation 页面内稳定操作名。
 */
@Serializable
data class ActionTarget(
    val pageType: String,
    val operation: String,
)

/**
 * 可向用户和 Agent 发布的动作元数据。
 *
 * @param id 稳定动作标识。
 * @param version 动作契约版本。
 * @param title 用户可读标题。
 * @param description 用户可读说明。
 * @param inputSchema JSON 输入结构。
 * @param riskLevel 动作风险等级。
 * @param requiredPermissions 执行动作所需权限。
 * @param target 动作作用目标。
 * @param replayPolicy 请求重放策略。
 * @param reconciliationPolicy 结果未知时的对账策略。
 */
@Serializable
data class ActionDescriptor(
    val id: String,
    val version: Int,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    @SerialName("risk")
    val riskLevel: ActionRiskLevel,
    val requiredPermissions: Set<String>,
    val target: ActionTarget,
    val replayPolicy: ActionReplayPolicy,
    val reconciliationPolicy: ReconciliationPolicy,
)
