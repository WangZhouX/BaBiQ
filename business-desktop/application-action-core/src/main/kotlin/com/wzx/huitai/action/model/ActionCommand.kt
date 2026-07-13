package com.wzx.huitai.action.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 动作发起来源。 */
@Serializable
enum class ActionOrigin {
    @SerialName("user")
    USER,

    @SerialName("agent")
    AGENT,
}

/**
 * 动作绑定的不可变业务身份范围。
 *
 * @param desktopInstanceId 桌面安装实例标识。
 * @param desktopSessionId 当前桌面进程会话标识。
 * @param authSessionId 汇泰认证会话标识。
 * @param identityEpoch 身份切换递增序号。
 * @param userId 当前用户标识。
 * @param tenantId 当前租户标识。
 * @param platformId 当前平台标识。
 */
@Serializable
data class ActionIdentityScope(
    val desktopInstanceId: String,
    val desktopSessionId: String,
    val authSessionId: String,
    val identityEpoch: Long,
    val userId: String,
    val tenantId: String,
    val platformId: String,
) {
    /** 日志中只保留身份代次，避免暴露完整业务身份。 */
    override fun toString(): String = "ActionIdentityScope(identityEpoch=$identityEpoch, values=[REDACTED])"
}

/**
 * 用户点击和 Agent 调用共用的动作命令。
 *
 * @param executionId 本地与远程共用的幂等执行标识。
 * @param actionId 目标动作标识。
 * @param actionVersion 创建命令时冻结的动作版本。
 * @param input JSON 动作输入。
 * @param origin 动作发起来源。
 * @param identityScope 创建命令时冻结的身份范围。
 * @param pageId 创建命令时所在页面标识。
 * @param contextRevision 创建命令时的页面上下文版本。
 */
@Serializable
data class ActionCommand(
    val executionId: String,
    val actionId: String,
    val actionVersion: Int,
    val input: JsonObject,
    val origin: ActionOrigin,
    val identityScope: ActionIdentityScope,
    val pageId: String,
    val contextRevision: Long,
) {
    init {
        require(actionVersion > 0) { "动作版本必须为正整数" }
    }

    /** 日志中保留动作定位信息，隐藏身份和值载荷。 */
    override fun toString(): String =
        "ActionCommand(executionId=$executionId, actionId=$actionId, actionVersion=$actionVersion, origin=$origin, " +
            "pageId=$pageId, contextRevision=$contextRevision, input=[REDACTED], identityScope=[REDACTED])"
}
