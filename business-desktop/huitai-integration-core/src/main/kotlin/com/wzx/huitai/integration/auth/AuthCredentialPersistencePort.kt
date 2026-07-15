package com.wzx.huitai.integration.auth

/** 认证凭据持久化端口；生产 JCEKS 适配器由后续任务实现。 */
interface AuthCredentialPersistencePort {
    /** 加载本机已保存的凭据。 */
    suspend fun load(): AuthTokenSet?

    /** 原子替换完整凭据集合。 */
    suspend fun replace(tokens: AuthTokenSet)

    /** 删除当前持久凭据。 */
    suspend fun clear()
}
