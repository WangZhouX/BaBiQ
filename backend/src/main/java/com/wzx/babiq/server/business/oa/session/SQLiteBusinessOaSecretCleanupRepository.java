package com.wzx.babiq.server.business.oa.session;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.persistence.entity.BusinessOaSecretCleanupEntity;
import com.wzx.babiq.server.persistence.mapper.BusinessOaSecretCleanupMapper;
import com.wzx.babiq.server.persistence.service.PersistenceTime;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** SQLite/MyBatis-Plus OA SecretStore 引用耐久清理适配器。 */
@Repository
public class SQLiteBusinessOaSecretCleanupRepository implements BusinessOaSecretCleanupRepository {
    private static final Pattern FIXED_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final DateTimeFormatter SORTABLE_INSTANT_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private final BusinessOaSecretCleanupMapper mapper;

    public SQLiteBusinessOaSecretCleanupRepository(BusinessOaSecretCleanupMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public BusinessOaSecretCleanupRecord upsertReserved(
            String secretRef,
            String authSessionId,
            String reasonCode,
            String operationId,
            Instant now) {
        requireSecretRef(secretRef);
        requireAuthSessionId(authSessionId);
        requireFixedCode(reasonCode, "reasonCode");
        requireOperationId(operationId);
        requireInstant(now);
        try {
            if (mapper.upsertReserved(secretRef, authSessionId, reasonCode, operationId,
                    writeSortableInstant(now)) != 1) {
                throw new BusinessOaSecretCleanupException(
                        "SECRET_CLEANUP_STATE_CONFLICT",
                        "OA 密钥清理记录已进入删除流程");
            }
            BusinessOaSecretCleanupEntity stored = mapper.selectById(secretRef);
            return stored == null ? throwPersistenceFailure() : toRecord(stored);
        } catch (BusinessOaSecretCleanupException exception) {
            throw exception;
        } catch (RuntimeException ignored) {
            throw persistenceFailure();
        }
    }

    @Override
    @Transactional
    public boolean consumeReserved(String secretRef, String authSessionId) {
        requireSecretRef(secretRef);
        requireAuthSessionId(authSessionId);
        try {
            return mapper.consumeReserved(secretRef, authSessionId) == 1;
        } catch (RuntimeException ignored) {
            throw persistenceFailure();
        }
    }

    @Override
    @Transactional
    public BusinessOaSecretCleanupRecord upsertDeletePending(
            String secretRef,
            String authSessionId,
            String reasonCode,
            String operationId,
            Instant now) {
        requireSecretRef(secretRef);
        requireAuthSessionId(authSessionId);
        requireFixedCode(reasonCode, "reasonCode");
        requireOperationId(operationId);
        requireInstant(now);
        try {
            if (mapper.upsertDeletePending(secretRef, authSessionId, reasonCode, operationId,
                    writeSortableInstant(now)) != 1) {
                throw stateConflict();
            }
            BusinessOaSecretCleanupEntity stored = mapper.selectById(secretRef);
            return stored == null ? throwPersistenceFailure() : toRecord(stored);
        } catch (BusinessOaSecretCleanupException exception) {
            throw exception;
        } catch (RuntimeException ignored) {
            throw persistenceFailure();
        }
    }

    @Override
    public Optional<BusinessOaSecretCleanupRecord> findBySecretRef(String secretRef) {
        requireSecretRef(secretRef);
        try {
            return Optional.ofNullable(mapper.selectById(secretRef)).map(SQLiteBusinessOaSecretCleanupRepository::toRecord);
        } catch (RuntimeException ignored) {
            throw persistenceFailure();
        }
    }

    @Override
    @Transactional
    public boolean markDeletePending(
            String secretRef,
            String reasonCode,
            String operationId,
            Instant now) {
        requireSecretRef(secretRef);
        requireFixedCode(reasonCode, "reasonCode");
        requireOperationId(operationId);
        requireInstant(now);
        try {
            return mapper.markDeletePending(secretRef, reasonCode, operationId, writeSortableInstant(now)) == 1;
        } catch (RuntimeException ignored) {
            throw persistenceFailure();
        }
    }

    @Override
    @Transactional
    public boolean markReservedDeletePending(
            String secretRef,
            String authSessionId,
            String reasonCode,
            String operationId,
            Instant now) {
        requireSecretRef(secretRef);
        requireAuthSessionId(authSessionId);
        requireFixedCode(reasonCode, "reasonCode");
        requireOperationId(operationId);
        requireInstant(now);
        try {
            return mapper.markReservedDeletePending(
                    secretRef,
                    authSessionId,
                    reasonCode,
                    operationId,
                    writeSortableInstant(now)) == 1;
        } catch (RuntimeException ignored) {
            throw persistenceFailure();
        }
    }

    @Override
    @Transactional
    public boolean recordDeleteFailure(String secretRef, String resultCode, Instant attemptedAt) {
        requireSecretRef(secretRef);
        requireFixedCode(resultCode, "resultCode");
        requireInstant(attemptedAt);
        try {
            return mapper.recordDeleteFailure(
                    secretRef, resultCode, writeSortableInstant(attemptedAt)) == 1;
        } catch (RuntimeException ignored) {
            throw persistenceFailure();
        }
    }

    @Override
    public List<BusinessOaSecretCleanupRecord> listByState(BusinessOaSecretCleanupState state) {
        if (state == null) throw new IllegalArgumentException("state 不能为空");
        try {
            return mapper.selectList(Wrappers.<BusinessOaSecretCleanupEntity>lambdaQuery()
                            .eq(BusinessOaSecretCleanupEntity::getState, state.name())
                            .orderByAsc(BusinessOaSecretCleanupEntity::getUpdatedAt)
                            .orderByAsc(BusinessOaSecretCleanupEntity::getSecretRef))
                    .stream().map(SQLiteBusinessOaSecretCleanupRepository::toRecord).toList();
        } catch (RuntimeException ignored) {
            throw persistenceFailure();
        }
    }

    @Override
    public List<BusinessOaSecretCleanupRecord> listDeletePendingBatch(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit 必须大于 0");
        try {
            return mapper.selectDeletePendingBatch(limit).stream()
                    .map(SQLiteBusinessOaSecretCleanupRepository::toRecord)
                    .toList();
        } catch (RuntimeException ignored) {
            throw persistenceFailure();
        }
    }

    @Override
    public boolean existsByAuthSessionId(String authSessionId) {
        requireAuthSessionId(authSessionId);
        try {
            return mapper.selectCount(Wrappers.<BusinessOaSecretCleanupEntity>lambdaQuery()
                    .eq(BusinessOaSecretCleanupEntity::getAuthSessionId, authSessionId)) > 0;
        } catch (RuntimeException ignored) {
            throw persistenceFailure();
        }
    }

    @Override
    @Transactional
    public boolean deleteTombstone(String secretRef) {
        requireSecretRef(secretRef);
        try {
            return mapper.deleteTombstone(secretRef) == 1;
        } catch (RuntimeException ignored) {
            throw persistenceFailure();
        }
    }

    private static BusinessOaSecretCleanupRecord toRecord(BusinessOaSecretCleanupEntity entity) {
        return new BusinessOaSecretCleanupRecord(
                entity.getSecretRef(),
                entity.getAuthSessionId(),
                BusinessOaSecretCleanupState.valueOf(entity.getState()),
                entity.getReasonCode(),
                entity.getOperationId(),
                entity.getAttemptCount() == null ? 0 : entity.getAttemptCount(),
                PersistenceTime.read(entity.getCreatedAt()),
                PersistenceTime.read(entity.getUpdatedAt()),
                PersistenceTime.read(entity.getLastAttemptAt()),
                entity.getLastResultCode());
    }

    private static void requireSecretRef(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            throw new IllegalArgumentException("secretRef 不能为空");
        }
    }

    private static void requireAuthSessionId(String authSessionId) {
        if (authSessionId == null || authSessionId.isBlank()) {
            throw new IllegalArgumentException("authSessionId 不能为空");
        }
    }

    private static void requireFixedCode(String code, String field) {
        if (code == null || !FIXED_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException(field + " 必须是固定内部码");
        }
    }

    private static void requireOperationId(String operationId) {
        if (operationId != null && (operationId.isBlank() || operationId.length() > 128)) {
            throw new IllegalArgumentException("operationId 格式无效");
        }
    }

    private static void requireInstant(Instant instant) {
        if (instant == null) throw new IllegalArgumentException("时间不能为空");
    }

    private static String writeSortableInstant(Instant instant) {
        return SORTABLE_INSTANT_FORMATTER.format(instant);
    }

    private static BusinessOaSecretCleanupException persistenceFailure() {
        return new BusinessOaSecretCleanupException(
                "SECRET_CLEANUP_PERSISTENCE_FAILED",
                "OA 密钥清理记录持久化失败");
    }

    private static BusinessOaSecretCleanupException stateConflict() {
        return new BusinessOaSecretCleanupException(
                "SECRET_CLEANUP_STATE_CONFLICT",
                "OA 密钥清理记录属于其他会话");
    }

    private static BusinessOaSecretCleanupRecord throwPersistenceFailure() {
        throw persistenceFailure();
    }
}
