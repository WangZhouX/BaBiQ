package com.wzx.huitai.integration.identity

import com.wzx.huitai.integration.auth.AuthIdentitySnapshot

/**
 * 发送给 Agent 连接的业务身份绑定快照。
 *
 * `identity == null` 明确表示已登出；`identityEpoch` 仍必须高于上一条已发布身份消息。
 */
data class AuthIdentityBinding(
    val identityEpoch: Long,
    val identity: AuthIdentitySnapshot?,
) {
    init {
        require(identityEpoch > 0) { "identityEpoch must be positive" }
        require(identity == null || identity.identityEpoch == identityEpoch) {
            "identityEpoch must match the bound identity"
        }
    }

    val signedOut: Boolean
        get() = identity == null
}

/**
 * integration core 到后续 Agent 客户端适配器的单向身份发布端口。
 *
 * 本模块只定义 bind/update 语义，不依赖 Task 17 的 JSON-RPC 实现。
 */
interface IdentityBindingPort {
    /** 当前连接首次建立业务身份时发送 bind。 */
    suspend fun bind(binding: AuthIdentityBinding)

    /** 已绑定连接发生用户、租户或登出边界变化时发送 update。 */
    suspend fun update(binding: AuthIdentityBinding)
}
