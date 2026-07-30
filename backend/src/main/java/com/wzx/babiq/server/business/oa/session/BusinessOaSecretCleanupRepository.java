package com.wzx.babiq.server.business.oa.session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** OA SecretStore 引用耐久清理记录的领域持久化端口。 */
public interface BusinessOaSecretCleanupRepository {
    BusinessOaSecretCleanupRecord upsertReserved(
            String secretRef,
            String authSessionId,
            String reasonCode,
            String operationId,
            Instant now);

    /** 仅消费仍属于同一认证会话的 RESERVED 引用。 */
    boolean consumeReserved(String secretRef, String authSessionId);

    /** 为已消费或历史 active 引用创建待删除记录；重复调度不得覆盖既有失败审计。 */
    BusinessOaSecretCleanupRecord upsertDeletePending(
            String secretRef,
            String authSessionId,
            String reasonCode,
            String operationId,
            Instant now);

    Optional<BusinessOaSecretCleanupRecord> findBySecretRef(String secretRef);

    boolean markDeletePending(
            String secretRef,
            String reasonCode,
            String operationId,
            Instant now);

    /**
     * Converts only the exact RESERVED tombstone still owned by the supplied authentication session.
     * A missing, consumed, reassigned or already-pending tombstone must return false.
     */
    default boolean markReservedDeletePending(
            String secretRef,
            String authSessionId,
            String reasonCode,
            String operationId,
            Instant now) {
        return false;
    }

    boolean recordDeleteFailure(String secretRef, String resultCode, Instant attemptedAt);

    List<BusinessOaSecretCleanupRecord> listByState(BusinessOaSecretCleanupState state);

    /** 按更新时间和引用稳定排序，读取一批待删除记录。 */
    List<BusinessOaSecretCleanupRecord> listDeletePendingBatch(int limit);

    boolean existsByAuthSessionId(String authSessionId);

    /** 仅在引用仍为 DELETE_PENDING 时完成条件删除。 */
    boolean deleteTombstone(String secretRef);
}
