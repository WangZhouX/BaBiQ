package com.wzx.huitai.integration.permission

import java.util.Collections

/**
 * 目标业务身份的角色与权限快照。
 *
 * 构造时冻结集合，避免租户切换完成后被上游可变集合篡改授权边界。
 */
class PermissionSnapshot(
    roles: Set<String>,
    permissions: Set<String>,
) {
    /** 目标身份的不可变角色集合，由权限适配器写入并由认证会话读取。 */
    val roles: Set<String> = immutableCopy(roles)

    /** 目标身份的不可变权限集合，由权限适配器写入并由认证会话读取。 */
    val permissions: Set<String> = immutableCopy(permissions)
}

/**
 * 按目标用户、租户和平台加载权威权限快照。
 *
 * 真实 OA 适配器由后续任务提供，本模块只依赖此端口完成切换编排。
 */
fun interface PermissionSnapshotProvider {
    /** 在发布目标身份前读取该身份的完整角色与权限。 */
    suspend fun load(userId: String, tenantId: String, platformId: String): PermissionSnapshot
}

private fun <T> immutableCopy(values: Set<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
