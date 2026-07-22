package com.wzx.huitai.desktop.auth

import com.wzx.huitai.integration.oa.auth.OaTenantCandidate

enum class BusinessSliderState { IDLE, REQUESTED }

enum class BusinessLoginErrorCode {
    INVALID_ACCOUNT,
    INVALID_PASSWORD_FORMAT,
    AGREEMENT_REQUIRED,
    ACCOUNT_NOT_FOUND,
    TENANT_UNAVAILABLE,
    TENANT_SELECTION_CANCELLED,
    INVALID_CREDENTIALS,
    REMOTE_UNAVAILABLE,
    REMOTE_TIMEOUT,
    REMOTE_PROTOCOL_ERROR,
    PERMISSION_LOAD_FAILED,
    LOCAL_CREDENTIAL_STORE_FAILED,
    LOCAL_KEYSTORE_UNAVAILABLE,
    AGENT_REGISTRATION_FAILED,
    AUTH_EXPIRED,
    MEMBERSHIP_EXPIRED,
    REMEMBERED_LOGIN_INVALID,
    AUTHENTICATION_IN_PROGRESS,
}

data class BusinessLoginMessage(
    val code: BusinessLoginErrorCode,
    val message: String = code.defaultMessage(),
)

data class BusinessTenantCandidateState(
    val candidate: OaTenantCandidate,
    val enabled: Boolean,
)

data class BusinessLoginState(
    val account: String = "",
    val password: String = "",
    val remember: Boolean = true,
    val agreementAccepted: Boolean = false,
    val slider: BusinessSliderState = BusinessSliderState.IDLE,
    val submitting: Boolean = false,
    val tenantCandidates: List<BusinessTenantCandidateState> = emptyList(),
    val error: BusinessLoginMessage? = null,
    val notice: BusinessLoginMessage? = null,
) {
    override fun toString(): String =
        "BusinessLoginState(account=[REDACTED], password=[REDACTED], remember=$remember, " +
            "agreementAccepted=$agreementAccepted, slider=$slider, submitting=$submitting, " +
            "tenantCandidates=${tenantCandidates.size}, error=${error?.code}, notice=${notice?.code})"
}

class BusinessLoginException(val code: BusinessLoginErrorCode) : IllegalStateException(code.name) {
    override fun toString(): String = "BusinessLoginException(code=${code.name})"
}

class BusinessAuthenticationException(val code: BusinessLoginErrorCode) : IllegalStateException(code.name) {
    override fun toString(): String = "BusinessAuthenticationException(code=${code.name})"
}

internal fun BusinessLoginErrorCode.defaultMessage(): String = when (this) {
    BusinessLoginErrorCode.INVALID_ACCOUNT -> "请输入有效的手机号或邮箱"
    BusinessLoginErrorCode.INVALID_PASSWORD_FORMAT -> "密码须为 8-16 位字母和数字组合"
    BusinessLoginErrorCode.AGREEMENT_REQUIRED -> "请先阅读并同意服务协议与隐私政策"
    BusinessLoginErrorCode.ACCOUNT_NOT_FOUND -> "未找到该账号"
    BusinessLoginErrorCode.TENANT_UNAVAILABLE -> "当前账号没有可用租户"
    BusinessLoginErrorCode.TENANT_SELECTION_CANCELLED -> "已取消租户选择"
    BusinessLoginErrorCode.INVALID_CREDENTIALS -> "账号或密码错误"
    BusinessLoginErrorCode.REMOTE_TIMEOUT -> "登录服务响应超时，请稍后重试"
    BusinessLoginErrorCode.REMOTE_UNAVAILABLE -> "登录服务暂不可用，请稍后重试"
    BusinessLoginErrorCode.REMOTE_PROTOCOL_ERROR -> "登录服务返回异常，请联系管理员"
    BusinessLoginErrorCode.PERMISSION_LOAD_FAILED -> "权限加载失败，请重新登录"
    BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED -> "本地凭据保存失败"
    BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE -> "本地安全存储不可用"
    BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED -> "智能助手初始化失败，请重新登录"
    BusinessLoginErrorCode.AUTH_EXPIRED -> "登录已过期，请重新登录"
    BusinessLoginErrorCode.MEMBERSHIP_EXPIRED -> "当前成员身份已失效，请重新登录"
    BusinessLoginErrorCode.REMEMBERED_LOGIN_INVALID -> "已保存的登录信息无效，请重新输入"
    BusinessLoginErrorCode.AUTHENTICATION_IN_PROGRESS -> "登录正在处理中"
}
