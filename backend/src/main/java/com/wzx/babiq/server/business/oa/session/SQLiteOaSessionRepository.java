package com.wzx.babiq.server.business.oa.session;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.persistence.entity.OaSessionEntity;
import com.wzx.babiq.server.persistence.mapper.OaSessionMapper;
import com.wzx.babiq.server.persistence.service.PersistenceTime;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** SQLite/MyBatis-Plus OA 会话索引适配器。 */
@Repository
public class SQLiteOaSessionRepository implements OaSessionRepository {
    private final OaSessionMapper mapper;

    public SQLiteOaSessionRepository(OaSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<OaSessionRecord> findByAuthSessionId(String authSessionId) {
        return Optional.ofNullable(mapper.selectById(authSessionId)).map(SQLiteOaSessionRepository::toRecord);
    }

    @Override
    public Optional<OaSessionRecord> findByDesktopSession(String instanceId, String sessionId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<OaSessionEntity>lambdaQuery()
                .eq(OaSessionEntity::getDesktopInstanceId, instanceId)
                .eq(OaSessionEntity::getDesktopSessionId, sessionId))).map(SQLiteOaSessionRepository::toRecord);
    }

    @Override
    public boolean existsCredentialReference(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return true;
        }
        return mapper.selectCount(Wrappers.<OaSessionEntity>lambdaQuery()
                .and(query -> query
                        .eq(OaSessionEntity::getActiveCredentialRef, secretRef)
                        .or()
                        .eq(OaSessionEntity::getStagedCredentialRef, secretRef))) > 0;
    }

    @Override
    public Optional<OaSessionRecord> findLatestDetachedByDesktopInstanceId(String instanceId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<OaSessionEntity>lambdaQuery()
                .eq(OaSessionEntity::getDesktopInstanceId, instanceId)
                .eq(OaSessionEntity::getPhase, OaSessionPhase.DETACHED.name())
                .orderByDesc(OaSessionEntity::getUpdatedAt)
                .last("LIMIT 1"))).map(SQLiteOaSessionRepository::toRecord);
    }

    @Override
    @Transactional
    public OaSessionRecord insert(OaSessionRecord record) {
        mapper.insert(toEntity(record));
        return record;
    }

    @Override
    @Transactional
    public OaSessionRecord update(OaSessionRecord record) {
        mapper.updateById(toEntity(record));
        return record;
    }

    @Override
    @Transactional
    public boolean compareAndSwapGeneration(String authSessionId, long expectedGeneration, OaSessionRecord record) {
        int updated = mapper.update(toEntity(record), Wrappers.<OaSessionEntity>lambdaUpdate()
                .eq(OaSessionEntity::getAuthSessionId, authSessionId)
                .eq(OaSessionEntity::getGeneration, expectedGeneration));
        return updated == 1;
    }

    @Override
    @Transactional
    public boolean compareAndSwapExact(OaSessionRecord expected, OaSessionRecord next) {
        if (expected == null || next == null) {
            throw new IllegalArgumentException("exact CAS records cannot be null");
        }
        if (!expected.authSessionId().equals(next.authSessionId())) {
            throw new IllegalArgumentException("exact CAS cannot change authSessionId");
        }
        return mapper.compareAndSwapExact(toEntity(expected), toEntity(next)) == 1;
    }

    @Override
    @Transactional
    public boolean compareAndSwapStage(
            String authSessionId,
            OaSessionPhase expectedSourcePhase,
            long expectedGeneration,
            String expectedDesktopInstanceId,
            String expectedDesktopSessionId,
            String expectedActiveCredentialRef,
            OaSessionRecord record) {
        if (expectedSourcePhase != OaSessionPhase.AUTHENTICATING
                && expectedSourcePhase != OaSessionPhase.RESTORING
                && expectedSourcePhase != OaSessionPhase.READY) {
            return false;
        }
        var update = Wrappers.<OaSessionEntity>lambdaUpdate()
                .eq(OaSessionEntity::getAuthSessionId, authSessionId)
                .eq(OaSessionEntity::getPhase, expectedSourcePhase.name())
                .eq(OaSessionEntity::getGeneration, expectedGeneration)
                .eq(OaSessionEntity::getDesktopInstanceId, expectedDesktopInstanceId)
                .eq(OaSessionEntity::getDesktopSessionId, expectedDesktopSessionId)
                .isNull(OaSessionEntity::getStagedCredentialRef)
                .isNull(OaSessionEntity::getInstallationId)
                .isNull(OaSessionEntity::getInstallationOwnerDesktopInstanceId)
                .isNull(OaSessionEntity::getInstallationOwnerDesktopSessionId)
                .eq(OaSessionEntity::getInstallationTargetGeneration, 0L)
                .isNull(OaSessionEntity::getInstallationExpiresAt);
        if (expectedActiveCredentialRef == null) {
            update.isNull(OaSessionEntity::getActiveCredentialRef);
        } else {
            update.eq(OaSessionEntity::getActiveCredentialRef, expectedActiveCredentialRef);
        }
        return mapper.update(toEntity(record), update) == 1;
    }

    @Override
    @Transactional
    public boolean compareAndSwapDetachedLease(
            String authSessionId,
            long expectedGeneration,
            String expectedDesktopInstanceId,
            String expectedDesktopSessionId,
            OaSessionRecord record) {
        int updated = mapper.update(toEntity(record), Wrappers.<OaSessionEntity>lambdaUpdate()
                .eq(OaSessionEntity::getAuthSessionId, authSessionId)
                .eq(OaSessionEntity::getPhase, OaSessionPhase.DETACHED.name())
                .eq(OaSessionEntity::getGeneration, expectedGeneration)
                .eq(OaSessionEntity::getDesktopInstanceId, expectedDesktopInstanceId)
                .eq(OaSessionEntity::getDesktopSessionId, expectedDesktopSessionId));
        return updated == 1;
    }

    @Override
    @Transactional
    public boolean compareAndSwapInstallation(
            String authSessionId,
            long expectedGeneration,
            String expectedInstallationId,
            String expectedOwnerDesktopInstanceId,
            String expectedOwnerDesktopSessionId,
            long expectedTargetGeneration,
            String expectedActiveCredentialRef,
            String expectedStagedCredentialRef,
            OaSessionRecord record) {
        var update = Wrappers.<OaSessionEntity>lambdaUpdate()
                .eq(OaSessionEntity::getAuthSessionId, authSessionId)
                .eq(OaSessionEntity::getGeneration, expectedGeneration)
                .eq(OaSessionEntity::getPhase, OaSessionPhase.INSTALLING.name())
                .eq(OaSessionEntity::getInstallationId, expectedInstallationId)
                .eq(OaSessionEntity::getInstallationOwnerDesktopInstanceId,
                        expectedOwnerDesktopInstanceId)
                .eq(OaSessionEntity::getInstallationOwnerDesktopSessionId,
                        expectedOwnerDesktopSessionId)
                .eq(OaSessionEntity::getInstallationTargetGeneration, expectedTargetGeneration);
        if (expectedActiveCredentialRef == null) {
            update.isNull(OaSessionEntity::getActiveCredentialRef);
        } else {
            update.eq(OaSessionEntity::getActiveCredentialRef, expectedActiveCredentialRef);
        }
        if (expectedStagedCredentialRef == null) {
            update.isNull(OaSessionEntity::getStagedCredentialRef);
        } else {
            update.eq(OaSessionEntity::getStagedCredentialRef, expectedStagedCredentialRef);
        }
        return mapper.update(toEntity(record), update) == 1;
    }

    @Override
    @Transactional
    public boolean compareAndSwapRecoverySnapshot(
            String authSessionId,
            OaSessionPhase expectedPhase,
            long expectedGeneration,
            String expectedInstallationId,
            String expectedActiveCredentialRef,
            String expectedStagedCredentialRef,
            OaSessionRecord record) {
        var update = Wrappers.<OaSessionEntity>lambdaUpdate()
                .eq(OaSessionEntity::getAuthSessionId, authSessionId)
                .eq(OaSessionEntity::getPhase, expectedPhase.name())
                .eq(OaSessionEntity::getGeneration, expectedGeneration);
        if (expectedInstallationId == null) {
            update.isNull(OaSessionEntity::getInstallationId);
        } else {
            update.eq(OaSessionEntity::getInstallationId, expectedInstallationId);
        }
        if (expectedActiveCredentialRef == null) {
            update.isNull(OaSessionEntity::getActiveCredentialRef);
        } else {
            update.eq(OaSessionEntity::getActiveCredentialRef, expectedActiveCredentialRef);
        }
        if (expectedStagedCredentialRef == null) {
            update.isNull(OaSessionEntity::getStagedCredentialRef);
        } else {
            update.eq(OaSessionEntity::getStagedCredentialRef, expectedStagedCredentialRef);
        }
        return mapper.update(toEntity(record), update) == 1;
    }

    @Override
    public List<OaSessionRecord> listRecoverable() {
        return mapper.selectList(Wrappers.<OaSessionEntity>lambdaQuery()
                        .in(OaSessionEntity::getPhase,
                                OaSessionPhase.AUTHENTICATING.name(), OaSessionPhase.RESTORING.name(),
                                OaSessionPhase.INSTALLING.name(), OaSessionPhase.REVOKING.name()))
                .stream().map(SQLiteOaSessionRepository::toRecord).toList();
    }

    private static OaSessionEntity toEntity(OaSessionRecord record) {
        OaSessionEntity entity = new OaSessionEntity();
        entity.setAuthSessionId(record.authSessionId());
        entity.setDesktopInstanceId(record.desktopInstanceId());
        entity.setDesktopSessionId(record.desktopSessionId());
        entity.setUserId(record.userId());
        entity.setTenantId(record.tenantId());
        entity.setPlatformId(record.platformId());
        entity.setPhase(record.phase().name());
        entity.setGeneration(record.generation());
        entity.setActiveCredentialRef(record.activeCredentialRef());
        entity.setStagedCredentialRef(record.stagedCredentialRef());
        entity.setCredentialVersion(record.credentialVersion());
        entity.setInstallStartedAt(PersistenceTime.write(record.installStartedAt()));
        entity.setInstalledAt(PersistenceTime.write(record.installedAt()));
        entity.setDetachedAt(PersistenceTime.write(record.detachedAt()));
        entity.setRevokedAt(PersistenceTime.write(record.revokedAt()));
        entity.setUpdatedAt(PersistenceTime.write(record.updatedAt()));
        entity.setInstallationId(record.installationId());
        entity.setInstallationOwnerDesktopInstanceId(record.installationOwnerDesktopInstanceId());
        entity.setInstallationOwnerDesktopSessionId(record.installationOwnerDesktopSessionId());
        entity.setInstallationTargetGeneration(record.installationTargetGeneration());
        entity.setInstallationExpiresAt(PersistenceTime.write(record.installationExpiresAt()));
        return entity;
    }

    private static OaSessionRecord toRecord(OaSessionEntity entity) {
        return new OaSessionRecord(entity.getAuthSessionId(), entity.getDesktopInstanceId(), entity.getDesktopSessionId(),
                entity.getUserId(), entity.getTenantId(), entity.getPlatformId(), OaSessionPhase.valueOf(entity.getPhase()),
                entity.getGeneration() == null ? 0 : entity.getGeneration(), entity.getActiveCredentialRef(),
                entity.getStagedCredentialRef(), entity.getCredentialVersion() == null ? 0 : entity.getCredentialVersion(),
                PersistenceTime.read(entity.getInstallStartedAt()), PersistenceTime.read(entity.getInstalledAt()),
                PersistenceTime.read(entity.getDetachedAt()), PersistenceTime.read(entity.getRevokedAt()),
                PersistenceTime.read(entity.getUpdatedAt()), entity.getInstallationId(),
                entity.getInstallationOwnerDesktopInstanceId(), entity.getInstallationOwnerDesktopSessionId(),
                entity.getInstallationTargetGeneration() == null ? 0 : entity.getInstallationTargetGeneration(),
                PersistenceTime.read(entity.getInstallationExpiresAt()));
    }
}
