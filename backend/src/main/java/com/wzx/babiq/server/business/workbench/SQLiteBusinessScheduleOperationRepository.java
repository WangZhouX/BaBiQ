package com.wzx.babiq.server.business.workbench;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.persistence.entity.BusinessScheduleOperationEntity;
import com.wzx.babiq.server.persistence.mapper.BusinessScheduleOperationMapper;
import com.wzx.babiq.server.persistence.service.PersistenceTime;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Repository
public class SQLiteBusinessScheduleOperationRepository
        implements BusinessScheduleOperationRepository {
    private final BusinessScheduleOperationMapper mapper;

    public SQLiteBusinessScheduleOperationRepository(BusinessScheduleOperationMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public Claim claim(Request request, Instant now) {
        Objects.requireNonNull(request, "request");
        String timestamp = PersistenceTime.write(now);
        BusinessScheduleOperationEntity candidate = toEntity(request, timestamp);
        if (mapper.insertIgnore(candidate) == 1) {
            return new Claim(Decision.WON, toRecord(candidate));
        }
        BusinessScheduleOperationEntity existing = mapper.selectById(request.operationId());
        if (existing == null || !sameBinding(existing, request)
                || !request.requestFingerprint().equals(existing.getRequestFingerprint())) {
            return new Claim(Decision.CONFLICT, existing == null ? null : toRecord(existing));
        }
        if (State.FAILED.name().equals(existing.getState())) {
            int won = mapper.update(null, Wrappers.<BusinessScheduleOperationEntity>lambdaUpdate()
                    .set(BusinessScheduleOperationEntity::getState, State.IN_FLIGHT.name())
                    .set(BusinessScheduleOperationEntity::getResultRevision, null)
                    .set(BusinessScheduleOperationEntity::getUpdatedAt, timestamp)
                    .eq(BusinessScheduleOperationEntity::getOperationId, request.operationId())
                    .eq(BusinessScheduleOperationEntity::getRequestFingerprint, request.requestFingerprint())
                    .eq(BusinessScheduleOperationEntity::getState, State.FAILED.name()));
            if (won == 1) {
                existing.setState(State.IN_FLIGHT.name());
                existing.setResultRevision(null);
                existing.setUpdatedAt(timestamp);
                return new Claim(Decision.WON, toRecord(existing));
            }
            existing = mapper.selectById(request.operationId());
        }
        return new Claim(decision(existing), toRecord(existing));
    }

    @Override
    public boolean complete(String operationId, String fingerprint, long revision, Instant now) {
        return transition(operationId, fingerprint, State.IN_FLIGHT, State.COMPLETED, revision, now);
    }

    @Override
    public boolean markOutcomeUnknown(String operationId, String fingerprint, Instant now) {
        return transition(operationId, fingerprint, State.IN_FLIGHT, State.OUTCOME_UNKNOWN, null, now);
    }

    @Override
    public boolean markFailed(String operationId, String fingerprint, Instant now) {
        return transition(operationId, fingerprint, State.IN_FLIGHT, State.FAILED, null, now);
    }

    @Override
    public int recoverInFlight(Instant now) {
        return mapper.update(null, Wrappers.<BusinessScheduleOperationEntity>lambdaUpdate()
                .set(BusinessScheduleOperationEntity::getState, State.OUTCOME_UNKNOWN.name())
                .set(BusinessScheduleOperationEntity::getUpdatedAt, PersistenceTime.write(now))
                .eq(BusinessScheduleOperationEntity::getState, State.IN_FLIGHT.name()));
    }

    private boolean transition(String id, String fingerprint, State expected, State next,
                               Long revision, Instant now) {
        return mapper.update(null, Wrappers.<BusinessScheduleOperationEntity>lambdaUpdate()
                .set(BusinessScheduleOperationEntity::getState, next.name())
                .set(BusinessScheduleOperationEntity::getResultRevision, revision)
                .set(BusinessScheduleOperationEntity::getUpdatedAt, PersistenceTime.write(now))
                .eq(BusinessScheduleOperationEntity::getOperationId, id)
                .eq(BusinessScheduleOperationEntity::getRequestFingerprint, fingerprint)
                .eq(BusinessScheduleOperationEntity::getState, expected.name())) == 1;
    }

    private static Decision decision(BusinessScheduleOperationEntity entity) {
        return switch (State.valueOf(entity.getState())) {
            case IN_FLIGHT -> Decision.IN_FLIGHT;
            case COMPLETED -> Decision.COMPLETED;
            case OUTCOME_UNKNOWN -> Decision.OUTCOME_UNKNOWN;
            case FAILED -> Decision.CONFLICT;
        };
    }

    private static boolean sameBinding(BusinessScheduleOperationEntity entity, Request request) {
        return entity.getDesktopInstanceId().equals(request.desktopInstanceId())
                && entity.getDesktopSessionId().equals(request.desktopSessionId())
                && entity.getAuthSessionId().equals(request.authSessionId())
                && entity.getTenantId().equals(request.tenantId())
                && entity.getIdentityGeneration() == request.identityGeneration()
                && entity.getClientOperationId().equals(request.clientOperationId())
                && entity.getActorUserId().equals(request.actorUserId())
                && entity.getFormRevision() == request.formRevision()
                && Objects.equals(entity.getAttachmentBatchId(), request.attachmentBatchId());
    }

    private static BusinessScheduleOperationEntity toEntity(Request request, String timestamp) {
        BusinessScheduleOperationEntity entity = new BusinessScheduleOperationEntity();
        entity.setOperationId(request.operationId());
        entity.setDesktopInstanceId(request.desktopInstanceId());
        entity.setDesktopSessionId(request.desktopSessionId());
        entity.setAuthSessionId(request.authSessionId());
        entity.setTenantId(request.tenantId());
        entity.setIdentityGeneration(request.identityGeneration());
        entity.setClientOperationId(request.clientOperationId());
        entity.setActorUserId(request.actorUserId());
        entity.setFormRevision(request.formRevision());
        entity.setAttachmentBatchId(request.attachmentBatchId());
        entity.setRequestFingerprint(request.requestFingerprint());
        entity.setState(State.IN_FLIGHT.name());
        entity.setCreatedAt(timestamp);
        entity.setUpdatedAt(timestamp);
        return entity;
    }

    private static Record toRecord(BusinessScheduleOperationEntity entity) {
        return new Record(entity.getOperationId(), entity.getRequestFingerprint(),
                State.valueOf(entity.getState()), entity.getResultRevision());
    }
}
