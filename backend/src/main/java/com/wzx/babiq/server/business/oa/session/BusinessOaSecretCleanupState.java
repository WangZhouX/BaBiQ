package com.wzx.babiq.server.business.oa.session;

/** OA SecretStore 引用的耐久清理状态。 */
public enum BusinessOaSecretCleanupState {
    /** 引用已在数据库预留，JCEKS entry 可能尚未写入。 */
    RESERVED,
    /** 引用必须在事务外执行幂等删除。 */
    DELETE_PENDING
}
