package com.wzx.babiq.server.business.upload;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.persistence.entity.BusinessAttachmentBatchEntity;
import com.wzx.babiq.server.persistence.entity.BusinessAttachmentTicketEntity;
import com.wzx.babiq.server.persistence.entity.BusinessResourceHandleEntity;
import com.wzx.babiq.server.persistence.mapper.BusinessAttachmentBatchMapper;
import com.wzx.babiq.server.persistence.mapper.BusinessAttachmentTicketMapper;
import com.wzx.babiq.server.persistence.mapper.BusinessResourceHandleMapper;
import com.wzx.babiq.server.persistence.service.PersistenceTime;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

/** MyBatis-Plus/SQLite implementation of the attachment CAS state machines. */
@Repository
public class SQLiteBusinessAttachmentRepository implements BusinessAttachmentRepository {
    private final BusinessAttachmentBatchMapper batches;
    private final BusinessAttachmentTicketMapper tickets;
    private final BusinessResourceHandleMapper resources;

    public SQLiteBusinessAttachmentRepository(BusinessAttachmentBatchMapper batches,
                                              BusinessAttachmentTicketMapper tickets,
                                              BusinessResourceHandleMapper resources) {
        this.batches = batches;
        this.tickets = tickets;
        this.resources = resources;
    }

    @Override
    @Transactional
    public void create(BusinessAttachmentBatchRecord batch, BusinessAttachmentTicketRecord ticket) {
        if (!batch.batchId().equals(ticket.batchId())) {
            throw new IllegalArgumentException("ticket and batch do not match");
        }
        batches.insert(toEntity(batch));
        tickets.insert(toEntity(ticket));
    }

    @Override
    public Optional<BusinessAttachmentBatchRecord> findBatch(String batchId) {
        return Optional.ofNullable(batches.selectById(batchId)).map(SQLiteBusinessAttachmentRepository::toRecord);
    }

    @Override
    public Optional<BusinessAttachmentTicketRecord> findTicketByBatchId(String batchId) {
        return Optional.ofNullable(tickets.selectOne(Wrappers.<BusinessAttachmentTicketEntity>lambdaQuery()
                .eq(BusinessAttachmentTicketEntity::getBatchId, batchId))).map(SQLiteBusinessAttachmentRepository::toRecord);
    }

    @Override
    @Transactional
    public boolean claimUpload(String batchId, String ticketDigest, String desktopInstanceId,
                               String desktopSessionId, String authSessionId, String tenantId,
                               long generation, Instant now) {
        String timestamp = PersistenceTime.write(now);
        int ticketWon = tickets.update(null, Wrappers.<BusinessAttachmentTicketEntity>lambdaUpdate()
                .set(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.CLAIMED.name())
                .set(BusinessAttachmentTicketEntity::getClaimedAt, timestamp)
                .set(BusinessAttachmentTicketEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentTicketEntity::getTicketId, ticketDigest)
                .eq(BusinessAttachmentTicketEntity::getBatchId, batchId)
                .eq(BusinessAttachmentTicketEntity::getDesktopInstanceId, desktopInstanceId)
                .eq(BusinessAttachmentTicketEntity::getDesktopSessionId, desktopSessionId)
                .eq(BusinessAttachmentTicketEntity::getAuthSessionId, authSessionId)
                .eq(BusinessAttachmentTicketEntity::getTenantId, tenantId)
                .eq(BusinessAttachmentTicketEntity::getIdentityGeneration, generation)
                .eq(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.ISSUED.name())
                .gt(BusinessAttachmentTicketEntity::getExpiresAt, timestamp));
        if (ticketWon != 1) return false;
        int inFlightWon = tickets.update(null, Wrappers.<BusinessAttachmentTicketEntity>lambdaUpdate()
                .set(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.IN_FLIGHT.name())
                .set(BusinessAttachmentTicketEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentTicketEntity::getTicketId, ticketDigest)
                .eq(BusinessAttachmentTicketEntity::getBatchId, batchId)
                .eq(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.CLAIMED.name()));
        if (inFlightWon != 1) throw new CasRollbackException();
        return true;
    }

    @Override
    @Transactional
    public boolean completeUpload(String batchId, String ticketDigest, String fileIdSecretRef, Instant now) {
        if (fileIdSecretRef == null || fileIdSecretRef.isBlank()) {
            throw new IllegalArgumentException("fileIdSecretRef must not be blank");
        }
        String timestamp = PersistenceTime.write(now);
        int batchWon = batches.update(null, Wrappers.<BusinessAttachmentBatchEntity>lambdaUpdate()
                .set(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.READY.name())
                .set(BusinessAttachmentBatchEntity::getFileIdSecretRef, fileIdSecretRef)
                .set(BusinessAttachmentBatchEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentBatchEntity::getBatchId, batchId)
                .eq(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.PENDING.name())
                .gt(BusinessAttachmentBatchEntity::getExpiresAt, timestamp));
        if (batchWon != 1) return false;
        int ticketWon = tickets.update(null, Wrappers.<BusinessAttachmentTicketEntity>lambdaUpdate()
                .set(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.SUCCEEDED.name())
                .set(BusinessAttachmentTicketEntity::getCompletedAt, timestamp)
                .set(BusinessAttachmentTicketEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentTicketEntity::getTicketId, ticketDigest)
                .eq(BusinessAttachmentTicketEntity::getBatchId, batchId)
                .eq(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.IN_FLIGHT.name()));
        if (ticketWon != 1) throw new CasRollbackException();
        return true;
    }

    @Override
    @Transactional
    public boolean transitionUpload(String batchId, String ticketDigest,
                                    BusinessAttachmentTicketService.TicketStatus expectedTicket,
                                    BusinessAttachmentTicketService.TicketStatus nextTicket,
                                    BusinessAttachmentTicketService.BatchStatus expectedBatch,
                                    BusinessAttachmentTicketService.BatchStatus nextBatch,
                                    Instant now) {
        String timestamp = PersistenceTime.write(now);
        int ticketWon = tickets.update(null, Wrappers.<BusinessAttachmentTicketEntity>lambdaUpdate()
                .set(BusinessAttachmentTicketEntity::getState, nextTicket.name())
                .set(BusinessAttachmentTicketEntity::getCompletedAt, timestamp)
                .set(BusinessAttachmentTicketEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentTicketEntity::getTicketId, ticketDigest)
                .eq(BusinessAttachmentTicketEntity::getBatchId, batchId)
                .eq(BusinessAttachmentTicketEntity::getState, expectedTicket.name()));
        if (ticketWon != 1) return false;
        int batchWon = batches.update(null, Wrappers.<BusinessAttachmentBatchEntity>lambdaUpdate()
                .set(BusinessAttachmentBatchEntity::getState, nextBatch.name())
                .set(BusinessAttachmentBatchEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentBatchEntity::getBatchId, batchId)
                .eq(BusinessAttachmentBatchEntity::getState, expectedBatch.name()));
        if (batchWon != 1) throw new CasRollbackException();
        return true;
    }

    @Override
    @Transactional
    public boolean beginScheduleConsumption(String batchId, String desktopInstanceId, String desktopSessionId,
                                            String authSessionId, String tenantId, long generation,
                                            String clientOperationId, String actorUserId,
                                            String scope, String teamId, String scheduleTypeId,
                                            String parentRelationType, String parentResourceId,
                                            String parentRecordId,
                                            String formRevision, Instant now) {
        String timestamp = PersistenceTime.write(now);
        var update = Wrappers.<BusinessAttachmentBatchEntity>lambdaUpdate()
                .set(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.CONSUMING.name())
                .set(BusinessAttachmentBatchEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentBatchEntity::getBatchId, batchId)
                .eq(BusinessAttachmentBatchEntity::getDesktopInstanceId, desktopInstanceId)
                .eq(BusinessAttachmentBatchEntity::getDesktopSessionId, desktopSessionId)
                .eq(BusinessAttachmentBatchEntity::getAuthSessionId, authSessionId)
                .eq(BusinessAttachmentBatchEntity::getTenantId, tenantId)
                .eq(BusinessAttachmentBatchEntity::getIdentityGeneration, generation)
                .eq(BusinessAttachmentBatchEntity::getOperation, "SCHEDULE_CREATE")
                .eq(BusinessAttachmentBatchEntity::getClientOperationId, clientOperationId)
                .eq(BusinessAttachmentBatchEntity::getActorUserId, actorUserId)
                .eq(BusinessAttachmentBatchEntity::getScope, scope)
                .eq(BusinessAttachmentBatchEntity::getScheduleTypeId, scheduleTypeId)
                .eq(BusinessAttachmentBatchEntity::getParentRelationType, parentRelationType)
                .eq(BusinessAttachmentBatchEntity::getParentResourceId, parentResourceId)
                .eq(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.READY.name())
                .isNotNull(BusinessAttachmentBatchEntity::getFileIdSecretRef)
                .gt(BusinessAttachmentBatchEntity::getExpiresAt, timestamp);
        if (teamId == null) update.isNull(BusinessAttachmentBatchEntity::getTeamId);
        else update.eq(BusinessAttachmentBatchEntity::getTeamId, teamId);
        if (parentRecordId == null) update.isNull(BusinessAttachmentBatchEntity::getParentRecordId);
        else update.eq(BusinessAttachmentBatchEntity::getParentRecordId, parentRecordId);
        if (formRevision == null) update.isNull(BusinessAttachmentBatchEntity::getFormRevision);
        else update.eq(BusinessAttachmentBatchEntity::getFormRevision, formRevision);
        return batches.update(null, update) == 1;
    }

    @Override
    public boolean finishScheduleConsumption(String batchId,
                                             BusinessAttachmentTicketService.BatchStatus nextState,
                                             Instant now) {
        if (nextState != BusinessAttachmentTicketService.BatchStatus.CONSUMED
                && nextState != BusinessAttachmentTicketService.BatchStatus.FAILED
                && nextState != BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN) {
            throw new IllegalArgumentException("invalid schedule consumption terminal state");
        }
        return batches.update(null, Wrappers.<BusinessAttachmentBatchEntity>lambdaUpdate()
                .set(BusinessAttachmentBatchEntity::getState, nextState.name())
                .set(BusinessAttachmentBatchEntity::getUpdatedAt, PersistenceTime.write(now))
                .eq(BusinessAttachmentBatchEntity::getBatchId, batchId)
                .eq(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.CONSUMING.name())) == 1;
    }

    @Override
    @Transactional
    public int revoke(
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long generation,
            Instant now) {
        String timestamp = PersistenceTime.write(now);
        List<String> inFlightBatchIds = tickets.selectList(
                        Wrappers.<BusinessAttachmentTicketEntity>lambdaQuery()
                                .select(BusinessAttachmentTicketEntity::getBatchId)
                                .eq(BusinessAttachmentTicketEntity::getDesktopInstanceId, desktopInstanceId)
                                .eq(BusinessAttachmentTicketEntity::getDesktopSessionId, desktopSessionId)
                                .eq(BusinessAttachmentTicketEntity::getAuthSessionId, authSessionId)
                                .eq(BusinessAttachmentTicketEntity::getIdentityGeneration, generation)
                                .in(BusinessAttachmentTicketEntity::getState,
                                        BusinessAttachmentTicketService.TicketStatus.CLAIMED.name(),
                                        BusinessAttachmentTicketService.TicketStatus.IN_FLIGHT.name()))
                .stream().map(BusinessAttachmentTicketEntity::getBatchId).toList();
        int unknownTickets = tickets.update(null, Wrappers.<BusinessAttachmentTicketEntity>lambdaUpdate()
                .set(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.OUTCOME_UNKNOWN.name())
                .set(BusinessAttachmentTicketEntity::getCompletedAt, timestamp)
                .set(BusinessAttachmentTicketEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentTicketEntity::getDesktopInstanceId, desktopInstanceId)
                .eq(BusinessAttachmentTicketEntity::getDesktopSessionId, desktopSessionId)
                .eq(BusinessAttachmentTicketEntity::getAuthSessionId, authSessionId)
                .eq(BusinessAttachmentTicketEntity::getIdentityGeneration, generation)
                .in(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.CLAIMED.name(),
                        BusinessAttachmentTicketService.TicketStatus.IN_FLIGHT.name()));
        int revokedTickets = tickets.update(null, Wrappers.<BusinessAttachmentTicketEntity>lambdaUpdate()
                .set(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.REVOKED.name())
                .set(BusinessAttachmentTicketEntity::getCompletedAt, timestamp)
                .set(BusinessAttachmentTicketEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentTicketEntity::getDesktopInstanceId, desktopInstanceId)
                .eq(BusinessAttachmentTicketEntity::getDesktopSessionId, desktopSessionId)
                .eq(BusinessAttachmentTicketEntity::getAuthSessionId, authSessionId)
                .eq(BusinessAttachmentTicketEntity::getIdentityGeneration, generation)
                .eq(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.ISSUED.name()));
        if (!inFlightBatchIds.isEmpty()) {
            batches.update(null, Wrappers.<BusinessAttachmentBatchEntity>lambdaUpdate()
                    .set(BusinessAttachmentBatchEntity::getState,
                            BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN.name())
                    .set(BusinessAttachmentBatchEntity::getUpdatedAt, timestamp)
                    .eq(BusinessAttachmentBatchEntity::getDesktopInstanceId, desktopInstanceId)
                    .eq(BusinessAttachmentBatchEntity::getDesktopSessionId, desktopSessionId)
                    .eq(BusinessAttachmentBatchEntity::getAuthSessionId, authSessionId)
                    .eq(BusinessAttachmentBatchEntity::getIdentityGeneration, generation)
                    .in(BusinessAttachmentBatchEntity::getBatchId, inFlightBatchIds)
                    .eq(BusinessAttachmentBatchEntity::getState,
                            BusinessAttachmentTicketService.BatchStatus.PENDING.name()));
        }
        batches.update(null, Wrappers.<BusinessAttachmentBatchEntity>lambdaUpdate()
                .set(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN.name())
                .set(BusinessAttachmentBatchEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentBatchEntity::getDesktopInstanceId, desktopInstanceId)
                .eq(BusinessAttachmentBatchEntity::getDesktopSessionId, desktopSessionId)
                .eq(BusinessAttachmentBatchEntity::getAuthSessionId, authSessionId)
                .eq(BusinessAttachmentBatchEntity::getIdentityGeneration, generation)
                .eq(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.CONSUMING.name()));
        batches.update(null, Wrappers.<BusinessAttachmentBatchEntity>lambdaUpdate()
                .set(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.REVOKED.name())
                .set(BusinessAttachmentBatchEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentBatchEntity::getDesktopInstanceId, desktopInstanceId)
                .eq(BusinessAttachmentBatchEntity::getDesktopSessionId, desktopSessionId)
                .eq(BusinessAttachmentBatchEntity::getAuthSessionId, authSessionId)
                .eq(BusinessAttachmentBatchEntity::getIdentityGeneration, generation)
                .in(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.PENDING.name(),
                        BusinessAttachmentTicketService.BatchStatus.READY.name()));
        return unknownTickets + revokedTickets;
    }

    @Override
    public List<String> fileIdSecretRefsForConnection(
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long generation) {
        return batches.selectList(Wrappers.<BusinessAttachmentBatchEntity>lambdaQuery()
                        .select(BusinessAttachmentBatchEntity::getFileIdSecretRef)
                        .eq(BusinessAttachmentBatchEntity::getDesktopInstanceId, desktopInstanceId)
                        .eq(BusinessAttachmentBatchEntity::getDesktopSessionId, desktopSessionId)
                        .eq(BusinessAttachmentBatchEntity::getAuthSessionId, authSessionId)
                        .eq(BusinessAttachmentBatchEntity::getIdentityGeneration, generation)
                        .isNotNull(BusinessAttachmentBatchEntity::getFileIdSecretRef)
                        .in(BusinessAttachmentBatchEntity::getState,
                                BusinessAttachmentTicketService.BatchStatus.READY.name(),
                                BusinessAttachmentTicketService.BatchStatus.CONSUMING.name()))
                .stream().map(BusinessAttachmentBatchEntity::getFileIdSecretRef).toList();
    }

    @Override
    public List<String> declarationSecretRefsForConnection(
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long generation) {
        return batches.selectList(Wrappers.<BusinessAttachmentBatchEntity>lambdaQuery()
                        .select(BusinessAttachmentBatchEntity::getDeclarationSecretRef)
                        .eq(BusinessAttachmentBatchEntity::getDesktopInstanceId, desktopInstanceId)
                        .eq(BusinessAttachmentBatchEntity::getDesktopSessionId, desktopSessionId)
                        .eq(BusinessAttachmentBatchEntity::getAuthSessionId, authSessionId)
                        .eq(BusinessAttachmentBatchEntity::getIdentityGeneration, generation)
                        .isNotNull(BusinessAttachmentBatchEntity::getDeclarationSecretRef)
                        .in(BusinessAttachmentBatchEntity::getState,
                                BusinessAttachmentTicketService.BatchStatus.PENDING.name(),
                                BusinessAttachmentTicketService.BatchStatus.READY.name(),
                                BusinessAttachmentTicketService.BatchStatus.CONSUMING.name()))
                .stream().map(BusinessAttachmentBatchEntity::getDeclarationSecretRef).toList();
    }

    @Override
    public List<String> recoverableFileIdSecretRefs(Instant now) {
        String timestamp = PersistenceTime.write(now);
        return batches.selectList(Wrappers.<BusinessAttachmentBatchEntity>lambdaQuery()
                        .select(BusinessAttachmentBatchEntity::getFileIdSecretRef)
                        .isNotNull(BusinessAttachmentBatchEntity::getFileIdSecretRef)
                        .and(wrapper -> wrapper.eq(BusinessAttachmentBatchEntity::getState,
                                        BusinessAttachmentTicketService.BatchStatus.CONSUMING.name())
                                .or(nested -> nested.eq(BusinessAttachmentBatchEntity::getState,
                                                BusinessAttachmentTicketService.BatchStatus.READY.name())
                                        .le(BusinessAttachmentBatchEntity::getExpiresAt, timestamp))))
                .stream().map(BusinessAttachmentBatchEntity::getFileIdSecretRef).toList();
    }

    @Override
    public List<String> recoverableDeclarationSecretRefs(Instant now) {
        String timestamp = PersistenceTime.write(now);
        return batches.selectList(Wrappers.<BusinessAttachmentBatchEntity>lambdaQuery()
                        .select(BusinessAttachmentBatchEntity::getDeclarationSecretRef)
                        .isNotNull(BusinessAttachmentBatchEntity::getDeclarationSecretRef)
                        .and(wrapper -> wrapper.in(BusinessAttachmentBatchEntity::getState,
                                        BusinessAttachmentTicketService.BatchStatus.PENDING.name(),
                                        BusinessAttachmentTicketService.BatchStatus.CONSUMING.name())
                                .or(nested -> nested.eq(BusinessAttachmentBatchEntity::getState,
                                                BusinessAttachmentTicketService.BatchStatus.READY.name())
                                        .le(BusinessAttachmentBatchEntity::getExpiresAt, timestamp))))
                .stream().map(BusinessAttachmentBatchEntity::getDeclarationSecretRef).toList();
    }

    @Override
    @Transactional
    public RecoveryCounts recover(Instant now) {
        String timestamp = PersistenceTime.write(now);
        var inFlightBatchIds = tickets.selectList(Wrappers.<BusinessAttachmentTicketEntity>lambdaQuery()
                        .in(BusinessAttachmentTicketEntity::getState,
                                BusinessAttachmentTicketService.TicketStatus.CLAIMED.name(),
                                BusinessAttachmentTicketService.TicketStatus.IN_FLIGHT.name()))
                .stream().map(BusinessAttachmentTicketEntity::getBatchId).toList();
        var issuedBatchIds = tickets.selectList(Wrappers.<BusinessAttachmentTicketEntity>lambdaQuery()
                        .eq(BusinessAttachmentTicketEntity::getState,
                                BusinessAttachmentTicketService.TicketStatus.ISSUED.name()))
                .stream().map(BusinessAttachmentTicketEntity::getBatchId).toList();
        int unknownTickets = tickets.update(null, Wrappers.<BusinessAttachmentTicketEntity>lambdaUpdate()
                .set(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.OUTCOME_UNKNOWN.name())
                .set(BusinessAttachmentTicketEntity::getCompletedAt, timestamp)
                .set(BusinessAttachmentTicketEntity::getUpdatedAt, timestamp)
                .in(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.CLAIMED.name(),
                        BusinessAttachmentTicketService.TicketStatus.IN_FLIGHT.name()));
        int unknownBatches = 0;
        if (!inFlightBatchIds.isEmpty()) {
            unknownBatches += batches.update(null, Wrappers.<BusinessAttachmentBatchEntity>lambdaUpdate()
                    .set(BusinessAttachmentBatchEntity::getState,
                            BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN.name())
                    .set(BusinessAttachmentBatchEntity::getUpdatedAt, timestamp)
                    .in(BusinessAttachmentBatchEntity::getBatchId, inFlightBatchIds)
                    .eq(BusinessAttachmentBatchEntity::getState,
                            BusinessAttachmentTicketService.BatchStatus.PENDING.name()));
        }
        unknownBatches += batches.update(null, Wrappers.<BusinessAttachmentBatchEntity>lambdaUpdate()
                .set(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN.name())
                .set(BusinessAttachmentBatchEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.CONSUMING.name()));
        int expiredTickets = tickets.update(null, Wrappers.<BusinessAttachmentTicketEntity>lambdaUpdate()
                .set(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.REVOKED.name())
                .set(BusinessAttachmentTicketEntity::getCompletedAt, timestamp)
                .set(BusinessAttachmentTicketEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentTicketEntity::getState,
                        BusinessAttachmentTicketService.TicketStatus.ISSUED.name()));
        if (!issuedBatchIds.isEmpty()) {
            batches.update(null, Wrappers.<BusinessAttachmentBatchEntity>lambdaUpdate()
                    .set(BusinessAttachmentBatchEntity::getState,
                            BusinessAttachmentTicketService.BatchStatus.REVOKED.name())
                    .set(BusinessAttachmentBatchEntity::getUpdatedAt, timestamp)
                    .in(BusinessAttachmentBatchEntity::getBatchId, issuedBatchIds)
                    .eq(BusinessAttachmentBatchEntity::getState,
                            BusinessAttachmentTicketService.BatchStatus.PENDING.name()));
        }
        int revokedBatches = batches.update(null, Wrappers.<BusinessAttachmentBatchEntity>lambdaUpdate()
                .set(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.REVOKED.name())
                .set(BusinessAttachmentBatchEntity::getUpdatedAt, timestamp)
                .eq(BusinessAttachmentBatchEntity::getState,
                        BusinessAttachmentTicketService.BatchStatus.READY.name())
                .le(BusinessAttachmentBatchEntity::getExpiresAt, timestamp));
        return new RecoveryCounts(unknownTickets, unknownBatches, expiredTickets, revokedBatches,
                purgeExpiredResources(now));
    }

    @Override
    public void insertResource(BusinessResourceHandleRecord record) {
        resources.insert(toEntity(record));
    }

    @Override
    public Optional<BusinessResourceHandleRecord> findResource(String handleId) {
        return Optional.ofNullable(resources.selectById(handleId)).map(SQLiteBusinessAttachmentRepository::toRecord);
    }

    @Override
    public List<String> resourceStorageRefsForConnection(
            String desktopInstanceId, String desktopSessionId, long generation) {
        return resources.selectList(Wrappers.<BusinessResourceHandleEntity>lambdaQuery()
                        .select(BusinessResourceHandleEntity::getStorageRef)
                        .eq(BusinessResourceHandleEntity::getDesktopInstanceId, desktopInstanceId)
                        .eq(BusinessResourceHandleEntity::getDesktopSessionId, desktopSessionId)
                        .eq(BusinessResourceHandleEntity::getIdentityGeneration, generation)
                        .isNull(BusinessResourceHandleEntity::getRevokedAt))
                .stream().map(BusinessResourceHandleEntity::getStorageRef).toList();
    }

    @Override
    public List<String> expiredResourceStorageRefs(Instant now) {
        return resources.selectList(Wrappers.<BusinessResourceHandleEntity>lambdaQuery()
                        .select(BusinessResourceHandleEntity::getStorageRef)
                        .isNull(BusinessResourceHandleEntity::getRevokedAt)
                        .le(BusinessResourceHandleEntity::getExpiresAt, PersistenceTime.write(now)))
                .stream().map(BusinessResourceHandleEntity::getStorageRef).toList();
    }

    @Override
    public List<String> pendingResourceCleanupRefs() {
        return resources.selectList(Wrappers.<BusinessResourceHandleEntity>lambdaQuery()
                        .select(BusinessResourceHandleEntity::getStorageRef)
                        .isNotNull(BusinessResourceHandleEntity::getRevokedAt))
                .stream().map(BusinessResourceHandleEntity::getStorageRef).toList();
    }

    @Override
    public List<String> allResourceStorageRefs() {
        return resources.selectList(Wrappers.<BusinessResourceHandleEntity>lambdaQuery()
                        .select(BusinessResourceHandleEntity::getStorageRef))
                .stream().map(BusinessResourceHandleEntity::getStorageRef).toList();
    }

    @Override
    public boolean completeResourceCleanup(String storageRef) {
        return resources.delete(Wrappers.<BusinessResourceHandleEntity>lambdaQuery()
                .eq(BusinessResourceHandleEntity::getStorageRef, storageRef)
                .isNotNull(BusinessResourceHandleEntity::getRevokedAt)) > 0;
    }

    @Override
    public int revokeResources(
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long generation,
            Instant now) {
        return resources.update(null, Wrappers.<BusinessResourceHandleEntity>lambdaUpdate()
                .set(BusinessResourceHandleEntity::getRevokedAt, PersistenceTime.write(now))
                .eq(BusinessResourceHandleEntity::getDesktopInstanceId, desktopInstanceId)
                .eq(BusinessResourceHandleEntity::getDesktopSessionId, desktopSessionId)
                .eq(BusinessResourceHandleEntity::getAuthSessionId, authSessionId)
                .eq(BusinessResourceHandleEntity::getIdentityGeneration, generation)
                .isNull(BusinessResourceHandleEntity::getRevokedAt));
    }

    @Override
    public int purgeExpiredResources(Instant now) {
        String timestamp = PersistenceTime.write(now);
        return resources.update(null, Wrappers.<BusinessResourceHandleEntity>lambdaUpdate()
                .set(BusinessResourceHandleEntity::getRevokedAt, timestamp)
                .isNull(BusinessResourceHandleEntity::getRevokedAt)
                .le(BusinessResourceHandleEntity::getExpiresAt, timestamp));
    }

    private static BusinessAttachmentBatchEntity toEntity(BusinessAttachmentBatchRecord value) {
        BusinessAttachmentBatchEntity entity = new BusinessAttachmentBatchEntity();
        entity.setBatchId(value.batchId());
        entity.setDesktopInstanceId(value.desktopInstanceId());
        entity.setDesktopSessionId(value.desktopSessionId());
        entity.setAuthSessionId(value.authSessionId());
        entity.setTenantId(value.tenantId());
        entity.setIdentityGeneration(value.identityGeneration());
        entity.setOperation(value.operation());
        entity.setClientOperationId(value.clientOperationId());
        entity.setActorUserId(value.actorUserId());
        entity.setScope(value.scope());
        entity.setTeamId(value.teamId());
        entity.setScheduleTypeId(value.scheduleTypeId());
        entity.setParentRelationType(value.parentRelationType());
        entity.setParentResourceId(value.parentResourceId());
        entity.setParentRecordId(value.parentRecordId());
        entity.setFormRevision(value.formRevision());
        entity.setDeclarationSecretRef(value.declarationSecretRef());
        entity.setState(value.state().name());
        entity.setFileIdSecretRef(value.fileIdSecretRef());
        entity.setExpiresAt(PersistenceTime.write(value.expiresAt()));
        entity.setCreatedAt(PersistenceTime.write(value.createdAt()));
        entity.setUpdatedAt(PersistenceTime.write(value.updatedAt()));
        return entity;
    }

    private static BusinessAttachmentTicketEntity toEntity(BusinessAttachmentTicketRecord value) {
        BusinessAttachmentTicketEntity entity = new BusinessAttachmentTicketEntity();
        entity.setTicketId(value.ticketDigest());
        entity.setBatchId(value.batchId());
        entity.setDesktopInstanceId(value.desktopInstanceId());
        entity.setDesktopSessionId(value.desktopSessionId());
        entity.setAuthSessionId(value.authSessionId());
        entity.setTenantId(value.tenantId());
        entity.setIdentityGeneration(value.identityGeneration());
        entity.setState(value.state().name());
        entity.setExpiresAt(PersistenceTime.write(value.expiresAt()));
        entity.setClaimedAt(PersistenceTime.write(value.claimedAt()));
        entity.setCompletedAt(PersistenceTime.write(value.completedAt()));
        entity.setUpdatedAt(PersistenceTime.write(value.updatedAt()));
        return entity;
    }

    private static BusinessResourceHandleEntity toEntity(BusinessResourceHandleRecord value) {
        BusinessResourceHandleEntity entity = new BusinessResourceHandleEntity();
        entity.setHandleId(value.handleId());
        entity.setDesktopInstanceId(value.desktopInstanceId());
        entity.setDesktopSessionId(value.desktopSessionId());
        entity.setAuthSessionId(value.authSessionId());
        entity.setTenantId(value.tenantId());
        entity.setIdentityGeneration(value.identityGeneration());
        entity.setMediaType(value.mediaType());
        entity.setContentLength(value.contentLength());
        entity.setStorageRef(value.storageRef());
        entity.setPolicy(value.policy());
        entity.setCreatedAt(PersistenceTime.write(value.createdAt()));
        entity.setExpiresAt(PersistenceTime.write(value.expiresAt()));
        entity.setRevokedAt(PersistenceTime.write(value.revokedAt()));
        return entity;
    }

    private static BusinessAttachmentBatchRecord toRecord(BusinessAttachmentBatchEntity value) {
        return new BusinessAttachmentBatchRecord(value.getBatchId(), value.getDesktopInstanceId(),
                value.getDesktopSessionId(), value.getAuthSessionId(), value.getTenantId(),
                value.getIdentityGeneration(), value.getOperation(), value.getClientOperationId(),
                value.getActorUserId(), value.getScope(), value.getTeamId(), value.getScheduleTypeId(),
                value.getParentRelationType(), value.getParentResourceId(), value.getParentRecordId(),
                value.getFormRevision(),
                value.getDeclarationSecretRef(),
                BusinessAttachmentTicketService.BatchStatus.valueOf(value.getState()),
                value.getFileIdSecretRef(), PersistenceTime.read(value.getExpiresAt()),
                PersistenceTime.read(value.getCreatedAt()), PersistenceTime.read(value.getUpdatedAt()));
    }

    private static BusinessAttachmentTicketRecord toRecord(BusinessAttachmentTicketEntity value) {
        return new BusinessAttachmentTicketRecord(value.getTicketId(), value.getBatchId(),
                value.getDesktopInstanceId(), value.getDesktopSessionId(), value.getAuthSessionId(),
                value.getTenantId(), value.getIdentityGeneration(),
                BusinessAttachmentTicketService.TicketStatus.valueOf(value.getState()),
                PersistenceTime.read(value.getExpiresAt()), PersistenceTime.read(value.getClaimedAt()),
                PersistenceTime.read(value.getCompletedAt()), PersistenceTime.read(value.getUpdatedAt()));
    }

    private static BusinessResourceHandleRecord toRecord(BusinessResourceHandleEntity value) {
        return new BusinessResourceHandleRecord(value.getHandleId(), value.getDesktopInstanceId(),
                value.getDesktopSessionId(), value.getAuthSessionId(), value.getTenantId(),
                value.getIdentityGeneration(), value.getMediaType(), value.getContentLength(),
                value.getStorageRef(), value.getPolicy(), PersistenceTime.read(value.getCreatedAt()),
                PersistenceTime.read(value.getExpiresAt()), PersistenceTime.read(value.getRevokedAt()));
    }

    private static final class CasRollbackException extends IllegalStateException {
        private CasRollbackException() { super("attachment state changed concurrently"); }
    }
}
