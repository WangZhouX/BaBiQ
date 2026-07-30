package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.persistence.entity.BusinessAttachmentSecretCleanupEntity;
import com.wzx.babiq.server.persistence.mapper.BusinessAttachmentSecretCleanupMapper;
import com.wzx.babiq.server.persistence.service.PersistenceTime;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Repository
public class SQLiteBusinessAttachmentSecretCleanupRepository
        implements BusinessAttachmentSecretCleanupRepository {
    private static final Set<String> KINDS = Set.of("FILE_IDS", "DECLARATION");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private final BusinessAttachmentSecretCleanupMapper mapper;

    public SQLiteBusinessAttachmentSecretCleanupRepository(BusinessAttachmentSecretCleanupMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void upsertPending(String secretRef, String secretKind, String reasonCode, Instant now) {
        requireRef(secretRef);
        if (!KINDS.contains(secretKind)) throw new IllegalArgumentException("invalid attachment secret kind");
        if (reasonCode == null || !CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("invalid attachment cleanup reason");
        }
        if (mapper.upsertPending(secretRef, secretKind, reasonCode, PersistenceTime.write(now)) != 1) {
            throw new IllegalStateException("attachment cleanup tombstone was not persisted");
        }
    }

    @Override
    @Transactional
    public void recordFailure(String secretRef, Instant now) {
        requireRef(secretRef);
        if (mapper.recordFailure(secretRef, PersistenceTime.write(now)) != 1) {
            throw new IllegalStateException("attachment cleanup failure was not persisted");
        }
    }

    @Override
    public List<PendingSecret> listPending(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        return mapper.selectPending(limit).stream()
                .map(SQLiteBusinessAttachmentSecretCleanupRepository::toRecord).toList();
    }

    @Override
    @Transactional
    public void deleteTombstone(String secretRef) {
        requireRef(secretRef);
        mapper.deleteTombstone(secretRef);
    }

    private static PendingSecret toRecord(BusinessAttachmentSecretCleanupEntity value) {
        return new PendingSecret(value.getSecretRef(), value.getSecretKind(), value.getReasonCode(),
                value.getAttemptCount() == null ? 0 : value.getAttemptCount());
    }

    private static void requireRef(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("secretRef must not be blank");
    }
}
