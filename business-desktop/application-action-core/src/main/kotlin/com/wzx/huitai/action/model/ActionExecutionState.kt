package com.wzx.huitai.action.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 桌面动作从接收到终态的执行阶段。 */
@Serializable
enum class ActionExecutionState {
    @SerialName("received")
    RECEIVED,

    @SerialName("validating")
    VALIDATING,

    @SerialName("previewed")
    PREVIEWED,

    @SerialName("waiting_approval")
    WAITING_APPROVAL,

    @SerialName("executing")
    EXECUTING,

    @SerialName("succeeded")
    SUCCEEDED,

    @SerialName("failed")
    FAILED,

    @SerialName("canceled")
    CANCELED,

    @SerialName("expired")
    EXPIRED,

    @SerialName("outcome_unknown")
    OUTCOME_UNKNOWN,
}

/** 动作风险等级，用于选择唯一允许的执行路径。 */
@Serializable
enum class ActionRiskLevel {
    @SerialName("read_only")
    READ_ONLY,

    @SerialName("reversible_write")
    REVERSIBLE_WRITE,

    @SerialName("high_risk")
    HIGH_RISK,
}
