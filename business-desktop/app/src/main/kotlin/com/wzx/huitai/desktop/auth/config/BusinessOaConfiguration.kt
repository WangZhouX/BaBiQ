package com.wzx.huitai.desktop.auth.config

/**
 * OA 的非敏感启动配置。
 *
 * 配置对象刻意不携带来源路径，也不允许承载凭据；调用方只拿到完成校验后的不可变值。
 */
data class BusinessOaConfiguration(
    val baseUrl: String,
    val apiPrefix: String,
    val platformId: Int,
    val requestTimeoutMs: Long,
    val serviceAgreementUrl: String,
    val privacyPolicyUrl: String,
    val allowInsecureHttp: Boolean,
)

enum class BusinessOaConfigurationErrorCode {
    CONFIG_UNAVAILABLE,
    CONFIG_INVALID,
}

/** 对外只暴露稳定错误码，不把配置路径或底层 I/O 信息带入界面和日志。 */
class BusinessOaConfigurationException internal constructor(
    val code: BusinessOaConfigurationErrorCode,
) : IllegalStateException(code.name) {
    override fun toString(): String = "BusinessOaConfigurationException(code=${code.name})"
}
