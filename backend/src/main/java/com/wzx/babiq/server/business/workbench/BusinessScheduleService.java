package com.wzx.babiq.server.business.workbench;

import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import com.wzx.babiq.server.business.oa.client.OaWorkbenchGateway;
import com.wzx.babiq.server.business.oa.client.OaWorkbenchException;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.OaAuthenticatedRequestExecutor;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.upload.BusinessAttachmentTicketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Server-owned schedule mutations and option reads. No OA payload crosses this service unchanged. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessScheduleService {
    private static final Set<String> SORT_KINDS = Set.of("SHORTCUT", "SUMMARY");
    private static final int SORT_LOCK_STRIPES = 64;
    private static final Map<String, Integer> OA_RELATION_TYPES = Map.of(
            "CASE", 1,
            "CUSTOMER", 2,
            "VISIT", 3,
            "SERVICE", 4);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OaWorkbenchGateway gateway;
    private final BusinessOaSessionRegistry sessions;
    private final OaAuthenticatedRequestExecutor executor;
    private final BusinessAttachmentTicketService attachmentTickets;
    private final BusinessScheduleOperationRepository scheduleOperations;
    private final Map<Object, AtomicLong> revisions = new ConcurrentHashMap<>();
    private final Map<SortRevisionKey, Long> sortRevisionGenerations = new ConcurrentHashMap<>();
    private final ReentrantLock[] sortLocks = createSortLocks();
    private final Map<CreateKey, CreateOperation> createOperations = new ConcurrentHashMap<>();

    public BusinessScheduleService(OaWorkbenchGateway gateway, BusinessOaSessionRegistry sessions,
                                   OaAuthenticatedRequestExecutor executor) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.attachmentTickets = null;
        this.scheduleOperations = null;
    }

    public BusinessScheduleService(OaWorkbenchGateway gateway, BusinessOaSessionRegistry sessions,
                                   OaAuthenticatedRequestExecutor executor,
                                   BusinessAttachmentTicketService attachmentTickets) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.attachmentTickets = attachmentTickets;
        this.scheduleOperations = null;
    }

    @Autowired
    public BusinessScheduleService(OaWorkbenchGateway gateway, BusinessOaSessionRegistry sessions,
                                   OaAuthenticatedRequestExecutor executor,
                                   BusinessAttachmentTicketService attachmentTickets,
                                   BusinessScheduleOperationRepository scheduleOperations) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.attachmentTickets = Objects.requireNonNull(attachmentTickets, "attachmentTickets");
        this.scheduleOperations = Objects.requireNonNull(scheduleOperations, "scheduleOperations");
    }

    public BusinessWorkbenchDtos.MutationEnvelope updateSort(ReadyOaSessionLease lease,
                                                               TrustedBusinessIdentity identity,
                                                               String kind, List<String> ids,
                                                               long expectedRevision) {
        requireLease(lease, identity);
        if (!SORT_KINDS.contains(kind) || ids == null || ids.isEmpty()
                || ids.size() > BusinessWorkbenchPayloadLimits.MAX_ITEMS
                || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 256)
                || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("invalid sort request");
        }
        SortRevisionKey revisionKey = new SortRevisionKey(
                lease.authSessionId(), lease.tenantId(), identity.userId(), kind);
        ReentrantLock sortLock = sortLocks[Math.floorMod(revisionKey.hashCode(), sortLocks.length)];
        sortLock.lock();
        try {
            Long currentGeneration = sortRevisionGenerations.get(revisionKey);
            if (currentGeneration != null && lease.generation() < currentGeneration) {
                throw new IllegalStateException("BUSINESS_SESSION_STALE");
            }
            if (currentGeneration == null || lease.generation() > currentGeneration) {
                revisions.put(revisionKey, new AtomicLong());
                sortRevisionGenerations.put(revisionKey, lease.generation());
            }
            AtomicLong revision = revisions.computeIfAbsent(revisionKey, ignored -> new AtomicLong());
            if (revision.get() != expectedRevision) throw new IllegalStateException("BUSINESS_CONFLICT");
            int type = "SHORTCUT".equals(kind) ? 1 : 2;
            List<Map<String, Object>> canonical = executor.execute(lease, read(), token ->
                    "SHORTCUT".equals(kind)
                            ? gateway.shortcuts(lease.tenantId(), token)
                            : gateway.summary(lease.tenantId(), token));
            List<String> canonicalIds = canonical.stream()
                    .filter(item -> sortable(kind, item))
                    .map(BusinessScheduleService::identifier)
                    .filter(Objects::nonNull)
                    .toList();
            if (canonicalIds.size() != ids.size() || !new HashSet<>(canonicalIds).equals(new HashSet<>(ids))) {
                throw new IllegalArgumentException("sort ids must match canonical items");
            }
            Boolean updated = executor.execute(lease, OaAuthenticatedRequestExecutor.RequestKind.WRITE,
                    token -> gateway.updateSort(lease.tenantId(), token, type, List.copyOf(ids)));
            if (!Boolean.TRUE.equals(updated)) throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
            long next = revision.incrementAndGet();
            return new BusinessWorkbenchDtos.MutationEnvelope(identity.identityEpoch(), lease.generation(), next, true);
        } finally {
            sortLock.unlock();
        }
    }

    private static ReentrantLock[] createSortLocks() {
        ReentrantLock[] locks = new ReentrantLock[SORT_LOCK_STRIPES];
        java.util.Arrays.setAll(locks, ignored -> new ReentrantLock());
        return locks;
    }

    private static boolean sortable(String kind, Map<String, Object> item) {
        Object enabled = item.get("enabled");
        if (Boolean.FALSE.equals(enabled)
                || enabled instanceof Number number && number.intValue() == 0
                || enabled instanceof String text && "false".equalsIgnoreCase(text.trim())) {
            return false;
        }
        if (!"SHORTCUT".equals(kind)) return true;
        Object safe = BusinessWorkbenchDataSanitizer.sanitize("shortcuts", List.of(item));
        return safe instanceof List<?> values
                && !values.isEmpty()
                && values.getFirst() instanceof Map<?, ?> mapped
                && mapped.containsKey("path");
    }

    public BusinessWorkbenchDtos.ScheduleMonthEnvelope month(ReadyOaSessionLease lease,
                                                               TrustedBusinessIdentity identity,
                                                               BusinessWorkbenchDtos.ScheduleQuery query) {
        requireLease(lease, identity);
        YearMonth date = parseMonth(query.date());
        Object data = executor.execute(lease, read(), token -> gateway.scheduleCount(lease.tenantId(), token,
                date.toString(), query.scope(), query.teamId(), query.onlyMine()));
        return new BusinessWorkbenchDtos.ScheduleMonthEnvelope(
                identity.identityEpoch(),
                lease.generation(),
                BusinessWorkbenchDataSanitizer.sanitizeScheduleMonth(data));
    }

    public BusinessWorkbenchDtos.ScheduleDayEnvelope day(ReadyOaSessionLease lease,
                                                          TrustedBusinessIdentity identity,
                                                          BusinessWorkbenchDtos.ScheduleQuery query) {
        requireLease(lease, identity);
        parseDate(query.date());
        Object data = executor.execute(lease, read(), token -> gateway.scheduleDay(lease.tenantId(), token,
                query.date(), query.scope(), query.teamId(), query.onlyMine(), query.typeId()));
        return new BusinessWorkbenchDtos.ScheduleDayEnvelope(
                identity.identityEpoch(),
                lease.generation(),
                BusinessWorkbenchDataSanitizer.sanitizeScheduleDay(data));
    }

    public BusinessWorkbenchDtos.ScheduleCompletionResult setCompletion(ReadyOaSessionLease lease,
                                                                          TrustedBusinessIdentity identity,
                                                                          String scheduleId, boolean completed) {
        requireLease(lease, identity);
        if (scheduleId == null || scheduleId.isBlank()) throw new IllegalArgumentException("scheduleId must not be blank");
        Boolean result = executor.execute(lease, OaAuthenticatedRequestExecutor.RequestKind.WRITE,
                token -> gateway.setScheduleCompletion(lease.tenantId(), token, scheduleId, completed));
        long revision = revisions.computeIfAbsent("SCHEDULE", ignored -> new AtomicLong()).incrementAndGet();
        return new BusinessWorkbenchDtos.ScheduleCompletionResult(identity.identityEpoch(), lease.generation(),
                Boolean.TRUE.equals(result) ? completed : !completed, true, revision);
    }

    public BusinessWorkbenchDtos.ScheduleFormEnvelope form(ReadyOaSessionLease lease, TrustedBusinessIdentity identity,
                                                            String scope, String teamId) {
        requireLease(lease, identity);
        BusinessWorkbenchDtos.ScheduleQuery query = new BusinessWorkbenchDtos.ScheduleQuery("2000-01-01", scope, teamId, false, null);
        List<Map<String, Object>> types = executor.execute(lease, read(), token -> gateway.scheduleTypes(
                lease.tenantId(), token, scope, teamId));
        List<Map<String, Object>> members;
        if ("TEAM".equals(scope)) {
            members = executor.execute(lease, read(), token ->
                    gateway.scheduleMembers(lease.tenantId(), token, teamId));
            if (members.stream().noneMatch(item -> identity.userId().equals(userIdentifier(item)))) {
                throw new IllegalArgumentException("actor is not a current team member");
            }
            boolean leaderOrAdmin = executor.execute(lease, read(), token ->
                    gateway.isTeamLeaderOrAdmin(lease.tenantId(), token, teamId));
            if (!leaderOrAdmin) {
                members = members.stream()
                        .filter(item -> identity.userId().equals(userIdentifier(item)))
                        .toList();
            }
        } else {
            members = List.of(Map.of(
                    "id", identity.userId(), "userId", identity.userId(), "userName", "当前用户"));
        }
        long revision = revisions.computeIfAbsent("SCHEDULE_FORM", ignored -> new AtomicLong()).get();
        return new BusinessWorkbenchDtos.ScheduleFormEnvelope(identity.identityEpoch(), lease.generation(), revision,
                safeItems(types), safeItems(members));
    }

    public BusinessWorkbenchDtos.RelationOptionsEnvelope relationOptions(ReadyOaSessionLease lease,
                                                                          TrustedBusinessIdentity identity,
                                                                          String relationType, String keyword,
                                                                          String teamId, String parentId) {
        requireLease(lease, identity);
        if (!OA_RELATION_TYPES.containsKey(relationType)) throw new IllegalArgumentException("invalid relation type");
        validateTeam(teamId, identity);
        if ("SERVICE".equals(relationType) && (parentId != null && !parentId.isBlank())) {
            return serviceProjects(lease, identity, parentId, keyword, teamId);
        }
        List<Map<String, Object>> items = executor.execute(lease, read(), token -> gateway.relationOptions(
                lease.tenantId(), token, relationType, keyword, teamId, parentId));
        long revision = revisions.computeIfAbsent("RELATION_" + relationType, ignored -> new AtomicLong()).get();
        return new BusinessWorkbenchDtos.RelationOptionsEnvelope(identity.identityEpoch(), lease.generation(), revision,
                relationType, safeItems(items));
    }

    public BusinessWorkbenchDtos.RelationOptionsEnvelope serviceProjects(ReadyOaSessionLease lease,
                                                                           TrustedBusinessIdentity identity,
                                                                           String recordId, String keyword) {
        return serviceProjects(lease, identity, recordId, keyword, null);
    }

    public BusinessWorkbenchDtos.RelationOptionsEnvelope serviceProjects(ReadyOaSessionLease lease,
                                                                          TrustedBusinessIdentity identity,
                                                                          String recordId, String keyword,
                                                                          String teamId) {
        requireLease(lease, identity);
        validateTeam(teamId, identity);
        if (recordId == null || recordId.isBlank()) throw new IllegalArgumentException("recordId must not be blank");
        List<Map<String, Object>> serviceRecords = executor.execute(lease, read(), token ->
                gateway.relationOptions(lease.tenantId(), token, "SERVICE", null, teamId, null));
        if (serviceRecords.stream().noneMatch(item -> recordId.equals(identifier(item)))) {
            throw new IllegalArgumentException("service record is not authorized");
        }
        List<Map<String, Object>> items = executor.execute(lease, read(), token -> gateway.serviceProjects(
                lease.tenantId(), token, recordId, keyword));
        long revision = revisions.computeIfAbsent("SERVICE_PROJECT", ignored -> new AtomicLong()).get();
        return new BusinessWorkbenchDtos.RelationOptionsEnvelope(identity.identityEpoch(), lease.generation(), revision,
                "SERVICE_PROJECT", safeItems(items));
    }

    /** Revalidates the exact form/options/team/relation context before issuing an upload ticket. */
    public void authorizeAttachmentPrepare(ReadyOaSessionLease lease, TrustedBusinessIdentity identity,
                                           String scope, String teamId, String typeId,
                                           String parentRelationType, String parentResourceId,
                                           String parentRecordId,
                                           long formRevision) {
        requireLease(lease, identity);
        if (formRevision != revisions.computeIfAbsent("SCHEDULE_FORM", ignored -> new AtomicLong()).get()) {
            throw new IllegalStateException("BUSINESS_CONFLICT");
        }
        validateTeam(teamId, identity);
        List<Map<String, Object>> types = executor.execute(lease, read(), token ->
                gateway.scheduleTypes(lease.tenantId(), token, scope, teamId));
        if (types.stream().noneMatch(type -> typeId.equals(identifier(type)))) {
            throw new IllegalArgumentException("schedule type is not authorized");
        }
        if ("TEAM".equals(scope)) {
            List<Map<String, Object>> members = executor.execute(lease, read(), token ->
                    gateway.scheduleMembers(lease.tenantId(), token, teamId));
            if (members.stream().noneMatch(member -> identity.userId().equals(userIdentifier(member)))) {
                throw new IllegalArgumentException("actor is not a current team member");
            }
        }
        if (!OA_RELATION_TYPES.containsKey(parentRelationType)) {
            throw new IllegalArgumentException("invalid parent relation type");
        }
        List<Map<String, Object>> relations = executor.execute(lease, read(), token ->
                gateway.relationOptions(lease.tenantId(), token, parentRelationType, null, teamId, null));
        boolean authorized = relations.stream().anyMatch(item -> parentResourceId.equals(identifier(item)));
        if ("SERVICE".equals(parentRelationType)) {
            boolean recordAuthorized = parentRecordId != null && !parentRecordId.isBlank()
                    && relations.stream().anyMatch(record -> parentRecordId.equals(identifier(record)));
            if (recordAuthorized) {
                List<Map<String, Object>> projects = executor.execute(lease, read(), token ->
                        gateway.serviceProjects(lease.tenantId(), token, parentRecordId, null));
                authorized = projects.stream().anyMatch(project -> parentResourceId.equals(identifier(project)));
            } else {
                authorized = false;
            }
        }
        if (!authorized) {
            throw new IllegalArgumentException("attachment parent is not currently authorized");
        }
    }

    public BusinessWorkbenchDtos.MutationEnvelope create(ReadyOaSessionLease lease, TrustedBusinessIdentity identity,
                                                          BusinessWorkbenchDtos.ScheduleCreateRequest request) {
        requireLease(lease, identity);
        validateCreate(identity, request);
        CreateKey key = new CreateKey(lease.authSessionId(), lease.desktopInstanceId(), lease.desktopSessionId(),
                lease.tenantId(), lease.generation(), request.clientOperationId());
        if (scheduleOperations == null) {
            CreateOperation existing = createOperations.putIfAbsent(
                    key, new CreateOperation(request, CreateState.IN_FLIGHT, null));
            if (existing != null) {
                if (!existing.request().equals(request)) throw new IllegalStateException("BUSINESS_OPERATION_CONFLICT");
                if (existing.state() == CreateState.COMPLETED) return existing.result();
                throw new IllegalStateException(existing.state() == CreateState.OUTCOME_UNKNOWN
                        ? "BUSINESS_OPERATION_OUTCOME_UNKNOWN" : "BUSINESS_OPERATION_IN_FLIGHT");
            }
        }
        DurableOperation durable = null;
        try {
            durable = claimDurableOperation(lease, identity, request);
            if (durable != null && durable.completedRevision != null) {
                return new BusinessWorkbenchDtos.MutationEnvelope(
                        identity.identityEpoch(), lease.generation(), durable.completedRevision, true);
            }
            long currentFormRevision = revisions.computeIfAbsent("SCHEDULE_FORM", ignored -> new AtomicLong()).get();
            if (request.formRevision() != currentFormRevision) throw new IllegalStateException("BUSINESS_CONFLICT");
            String attachmentParentRecordId = attachmentParentRecordId(request);
            if (request.attachmentBatchId() != null) {
                if (attachmentTickets == null) throw new IllegalStateException("business attachment boundary unavailable");
                if (!attachmentTickets.canConsumeForScheduleCreate(request.attachmentBatchId(),
                        connection(identity), lease, request.clientOperationId(),
                        request.scope(), request.teamId(), request.typeId(),
                        request.attachmentParentResourceId(), attachmentParentRecordId)) {
                    throw new IllegalArgumentException("attachment batch is unavailable");
                }
            }
            List<Map<String, Object>> allowedTypes = executor.execute(lease, read(), token -> gateway.scheduleTypes(
                    lease.tenantId(), token, request.scope(), request.teamId()));
            if (allowedTypes.stream().noneMatch(type -> request.typeId().equals(identifier(type)))) {
                throw new IllegalArgumentException("schedule type is not authorized");
            }
            if ("TEAM".equals(request.scope())) {
                List<Map<String, Object>> members = executor.execute(lease, read(), token -> gateway.scheduleMembers(
                        lease.tenantId(), token, request.teamId()));
                if (members.stream().noneMatch(member -> identity.userId().equals(userIdentifier(member)))) {
                    throw new IllegalArgumentException("actor is not a current team member");
                }
                if (members.stream().noneMatch(member -> request.assigneeUserId().equals(userIdentifier(member)))) {
                    throw new IllegalArgumentException("assignee is not a team member");
                }
                if (!identity.userId().equals(request.assigneeUserId())) {
                    boolean leaderOrAdmin = executor.execute(lease, read(), token ->
                            gateway.isTeamLeaderOrAdmin(lease.tenantId(), token, request.teamId()));
                    if (!leaderOrAdmin) throw new IllegalArgumentException("member cannot assign another user");
                }
            }
            authorizeScheduleRelations(lease, request);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dataType", "TEAM".equals(request.scope()) ? 1 : 0);
            if (request.teamId() != null) payload.put("teamId", request.teamId());
            payload.put("userId", effectiveAssigneeUserId(identity, request));
            payload.put("schTitle", request.title());
            payload.put("schId", request.typeId());
            payload.put("schTime", request.allDay()
                    ? parseDateTime(request.at()).toLocalDate().atStartOfDay().format(DATE_TIME)
                    : request.at());
            payload.put("allDay", request.allDay() ? 1 : 0);
            payload.put("schEmergeDegree", request.priority());
            payload.put("schContent", request.description());
            payload.put("schRemTimes", request.reminderMinutes().stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse(null));
            payload.put("repetition", request.repetition());
            payload.put("relations", boundedRelations(request.relations()));
            BusinessAttachmentTicketService.ScheduleAttachmentConsumption attachment = null;
            if (request.attachmentBatchId() != null) {
                attachment = attachmentTickets.beginScheduleCreate(request.attachmentBatchId(),
                        connection(identity), lease, request.clientOperationId(), identity.userId(),
                        request.scope(), request.teamId(), request.typeId(),
                        request.attachmentParentRelationType(), request.attachmentParentResourceId(),
                        attachmentParentRecordId,
                        Long.toString(currentFormRevision));
                if (attachment != null) payload.put("fileIds", attachment.fileIds());
            }
            return createRemoteAndFinalize(lease, identity, request, key, payload, attachment, durable);
        } catch (RuntimeException failure) {
            // Once the remote write has started, preserve the unknown outcome marker so a retry cannot
            // accidentally create a duplicate schedule. Local preflight failures remain retryable.
            if (scheduleOperations == null) {
                CreateOperation current = createOperations.get(key);
                if (current == null || current.state() != CreateState.OUTCOME_UNKNOWN) {
                    createOperations.remove(key);
                }
            } else if (durable != null && !durable.remotePhaseEntered) {
                scheduleOperations.markFailed(
                        durable.operationId, durable.fingerprint, java.time.Instant.now());
            }
            throw failure;
        }
    }

    private BusinessWorkbenchDtos.MutationEnvelope createRemoteAndFinalize(ReadyOaSessionLease lease,
                                                                              TrustedBusinessIdentity identity,
                                                                              BusinessWorkbenchDtos.ScheduleCreateRequest request,
                                                                              CreateKey key,
                                                                              Map<String, Object> payload,
                                                                              BusinessAttachmentTicketService.ScheduleAttachmentConsumption attachment,
                                                                              DurableOperation durable) {
        java.util.concurrent.atomic.AtomicBoolean dispatched = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            executor.execute(lease, OaAuthenticatedRequestExecutor.RequestKind.WRITE,
                    token -> {
                        dispatched.set(true);
                        if (durable != null) durable.remotePhaseEntered = true;
                        return gateway.createSchedule(lease.tenantId(), token, payload);
                    });
        } catch (RuntimeException failure) {
            if (attachment != null) {
                BusinessAttachmentTicketService.BatchStatus terminalState = dispatched.get()
                        ? BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN
                        : BusinessAttachmentTicketService.BatchStatus.FAILED;
                attachmentTickets.finishScheduleCreate(attachment,
                        terminalState);
            }
            if (dispatched.get()) {
                terminalizeUnknown(key, request, durable);
            } else if (durable != null) {
                scheduleOperations.markFailed(
                        durable.operationId, durable.fingerprint, java.time.Instant.now());
            }
            throw failure;
        }
        if (attachment != null) {
            try {
                attachmentTickets.finishScheduleCreate(
                        attachment, BusinessAttachmentTicketService.BatchStatus.CONSUMED);
            } catch (RuntimeException failure) {
                // OA schedule creation already completed; attachment state is now indeterminate and
                // must not allow a caller retry to create a duplicate schedule.
                terminalizeUnknown(key, request, durable);
                throw failure;
            }
        } else if (request.attachmentBatchId() != null) {
            // Compatibility for narrow test adapters; production always returns a durable consumption lease.
            try {
                if (!attachmentTickets.consumeForScheduleCreate(
                        request.attachmentBatchId(), connection(identity), lease, request.clientOperationId(),
                        request.scope(), request.teamId(), request.typeId(),
                        request.attachmentParentResourceId(), attachmentParentRecordId(request))) {
                    terminalizeUnknown(key, request, durable);
                    throw new IllegalStateException("BUSINESS_ATTACHMENT_CONSUME_FAILED");
                }
            } catch (RuntimeException failure) {
                terminalizeUnknown(key, request, durable);
                throw failure;
            }
        }
        long revision = revisions.computeIfAbsent("SCHEDULE", ignored -> new AtomicLong()).incrementAndGet();
        BusinessWorkbenchDtos.MutationEnvelope envelope =
                new BusinessWorkbenchDtos.MutationEnvelope(identity.identityEpoch(), lease.generation(), revision, true);
        if (durable == null) {
            createOperations.put(key, new CreateOperation(request, CreateState.COMPLETED, envelope));
        } else if (!scheduleOperations.complete(
                durable.operationId, durable.fingerprint, revision, java.time.Instant.now())) {
            throw new IllegalStateException("BUSINESS_OPERATION_OUTCOME_UNKNOWN");
        }
        return envelope;
    }

    private DurableOperation claimDurableOperation(
            ReadyOaSessionLease lease,
            TrustedBusinessIdentity identity,
            BusinessWorkbenchDtos.ScheduleCreateRequest request) {
        if (scheduleOperations == null) return null;
        String operationId = digest(String.join("\u001f",
                lease.desktopInstanceId(), lease.desktopSessionId(), lease.authSessionId(),
                lease.tenantId(), Long.toString(lease.generation()), request.clientOperationId()));
        String fingerprint = digest(String.join("\u001f",
                request.clientOperationId(), request.scope(), String.valueOf(request.teamId()),
                effectiveAssigneeUserId(identity, request), request.title(), request.typeId(), request.at(),
                Boolean.toString(request.allDay()), Integer.toString(request.priority()),
                String.valueOf(request.description()), request.reminderMinutes().toString(),
                boundedRelations(request.relations()).toString(), String.valueOf(request.attachmentBatchId()),
                String.valueOf(request.attachmentParentResourceId()),
                String.valueOf(request.attachmentParentRelationType()),
                Long.toString(request.formRevision()), Integer.toString(request.repetition())));
        BusinessScheduleOperationRepository.Request durableRequest =
                new BusinessScheduleOperationRepository.Request(
                        operationId, lease.desktopInstanceId(), lease.desktopSessionId(),
                        lease.authSessionId(), lease.tenantId(), lease.generation(),
                        request.clientOperationId(), identity.userId(), request.formRevision(),
                        request.attachmentBatchId(), fingerprint);
        BusinessScheduleOperationRepository.Claim claim =
                scheduleOperations.claim(durableRequest, java.time.Instant.now());
        return switch (claim.decision()) {
            case WON -> new DurableOperation(operationId, fingerprint, null);
            case COMPLETED -> {
                Long revision = claim.record() == null ? null : claim.record().resultRevision();
                if (revision == null) throw new IllegalStateException("BUSINESS_OPERATION_OUTCOME_UNKNOWN");
                yield new DurableOperation(operationId, fingerprint, revision);
            }
            case IN_FLIGHT -> throw new IllegalStateException("BUSINESS_OPERATION_IN_FLIGHT");
            case OUTCOME_UNKNOWN -> throw new IllegalStateException("BUSINESS_OPERATION_OUTCOME_UNKNOWN");
            case CONFLICT -> throw new IllegalStateException("BUSINESS_OPERATION_CONFLICT");
        };
    }

    private void terminalizeUnknown(
            CreateKey key,
            BusinessWorkbenchDtos.ScheduleCreateRequest request,
            DurableOperation durable) {
        if (durable == null) {
            createOperations.put(key, new CreateOperation(request, CreateState.OUTCOME_UNKNOWN, null));
        } else {
            scheduleOperations.markOutcomeUnknown(
                    durable.operationId, durable.fingerprint, java.time.Instant.now());
        }
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void validateCreate(TrustedBusinessIdentity identity, BusinessWorkbenchDtos.ScheduleCreateRequest request) {
        Objects.requireNonNull(request, "request");
        if ("PERSONAL".equals(request.scope())
                && request.assigneeUserId() != null
                && !request.assigneeUserId().isBlank()
                && !identity.userId().equals(request.assigneeUserId())) {
            throw new IllegalArgumentException("personal assignee must match authenticated user");
        }
        parseDateTime(request.at());
        if (request.description() != null && request.description().length() > BusinessWorkbenchPayloadLimits.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("schedule description is too long");
        }
        if (request.reminderMinutes().size() > 20) {
            throw new IllegalArgumentException("too many schedule reminders");
        }
        if (request.reminderMinutes().stream().distinct().count() != request.reminderMinutes().size()
                || request.reminderMinutes().stream().anyMatch(minutes ->
                minutes == null || minutes == 0
                        || minutes < 0 && (!request.allDay()
                        || !Set.of(-540, -600, -840).contains(minutes)))) {
            throw new IllegalArgumentException("invalid schedule reminder");
        }
        if (request.relations().size() > 8) {
            throw new IllegalArgumentException("too many schedule relations");
        }
        for (Map<String, Object> relation : request.relations()) {
            if (relation == null) throw new IllegalArgumentException("invalid schedule relation");
            Integer relationType = oaRelationType(relation.get("relationType"));
            String relationId = relationIdentifier(relation.get("relationId"));
            if (relationType == null || relationId == null || relationId.isBlank()) {
                throw new IllegalArgumentException("invalid schedule relation");
            }
            if ("SERVICE".equals(relation.get("relationType"))
                    && relationIdentifier(relation.get("parentId")) == null) {
                throw new IllegalArgumentException("service relation requires parentId");
            }
        }
        if (request.attachmentBatchId() != null && request.relations().stream()
                .noneMatch(relation -> request.attachmentParentResourceId()
                        .equals(relationIdentifier(relation.get("relationId")))
                        && (request.attachmentParentRelationType() == null
                        || request.attachmentParentRelationType().equals(relation.get("relationType"))))) {
            throw new IllegalArgumentException("attachment parent relation is not selected");
        }
    }

    private static String effectiveAssigneeUserId(
            TrustedBusinessIdentity identity,
            BusinessWorkbenchDtos.ScheduleCreateRequest request) {
        return "PERSONAL".equals(request.scope()) ? identity.userId() : request.assigneeUserId();
    }

    private static String attachmentParentRecordId(BusinessWorkbenchDtos.ScheduleCreateRequest request) {
        if (!"SERVICE".equals(request.attachmentParentRelationType())
                || request.attachmentParentResourceId() == null) {
            return null;
        }
        return request.relations().stream()
                .filter(relation -> "SERVICE".equals(relation.get("relationType")))
                .filter(relation -> request.attachmentParentResourceId()
                        .equals(relationIdentifier(relation.get("relationId"))))
                .map(relation -> relationIdentifier(relation.get("parentId")))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private void authorizeScheduleRelations(ReadyOaSessionLease lease,
                                            BusinessWorkbenchDtos.ScheduleCreateRequest request) {
        for (Map<String, Object> relation : request.relations()) {
            String relationType = (String) relation.get("relationType");
            String relationId = relationIdentifier(relation.get("relationId"));
            if ("SERVICE".equals(relationType)) {
                String recordId = relationIdentifier(relation.get("parentId"));
                List<Map<String, Object>> records = executor.execute(lease, read(), token ->
                        gateway.relationOptions(lease.tenantId(), token, "SERVICE",
                                null, request.teamId(), null));
                if (records.stream().noneMatch(item -> recordId.equals(identifier(item)))) {
                    throw new IllegalArgumentException("schedule relation is not currently authorized");
                }
                List<Map<String, Object>> projects = executor.execute(lease, read(), token ->
                        gateway.serviceProjects(lease.tenantId(), token, recordId, null));
                if (projects.stream().noneMatch(item -> relationId.equals(identifier(item)))) {
                    throw new IllegalArgumentException("schedule relation is not currently authorized");
                }
                continue;
            }
            List<Map<String, Object>> options = executor.execute(lease, read(), token ->
                    gateway.relationOptions(lease.tenantId(), token, relationType,
                            null, request.teamId(), null));
            if (options.stream().noneMatch(item -> relationId.equals(identifier(item)))) {
                throw new IllegalArgumentException("schedule relation is not currently authorized");
            }
        }
    }

    /**
     * 仅构造 OA 日程关联协议允许的标量字段，避免未知字段或嵌套对象经字符串化泄漏到远端。
     */
    private static List<Map<String, Object>> boundedRelations(List<Map<String, Object>> relations) {
        return relations.stream().map(relation -> {
            Map<String, Object> bounded = new LinkedHashMap<>();
            bounded.put("relationType", oaRelationType(relation.get("relationType")));
            bounded.put("relationId", relationIdentifier(relation.get("relationId")));
            if (relation.get("relationTitle") instanceof String title) {
                bounded.put("relationTitle", BusinessWorkbenchPayloadLimits.text(
                        title, BusinessWorkbenchPayloadLimits.MAX_TEXT_LENGTH));
            }
            return bounded;
        }).toList();
    }

    private static Integer oaRelationType(Object value) {
        if (!(value instanceof String text)) return null;
        String normalized = text.trim();
        return normalized.isEmpty() ? null : OA_RELATION_TYPES.get(normalized);
    }

    /**
     * 将桌面端字符串或 JSON 整数 ID 规范化为稳定文本；结构值和非整数数值一律拒绝。
     */
    private static String relationIdentifier(Object value) {
        if (value instanceof String text) {
            String normalized = text.trim();
            return normalized.isEmpty() ? null : normalized;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return Long.toString(((Number) value).longValue());
        }
        return null;
    }

    private void validateTeam(String teamId, TrustedBusinessIdentity identity) {
        if (teamId != null && !teamId.isBlank() && !"*".equals(identity.platformId())
                && !identity.permissions().contains("*") && !identity.permissions().contains("team:" + teamId)) {
            // Identity projection currently does not expose an arbitrary team list. Keep missing proof fail-closed.
            throw new IllegalArgumentException("team is not authorized");
        }
    }

    private void requireLease(ReadyOaSessionLease lease, TrustedBusinessIdentity identity) {
        if (lease == null || identity == null || !sessions.isCurrent(lease)
                || !lease.authSessionId().equals(identity.authSessionId())
                || !lease.desktopSessionId().equals(identity.desktopSessionId())
                || !lease.tenantId().equals(identity.tenantId())) throw new IllegalStateException("BUSINESS_SESSION_STALE");
    }

    private static LocalDate parseDate(String value) {
        try { return LocalDate.parse(value); } catch (DateTimeParseException failure) { throw new IllegalArgumentException("date must be yyyy-MM-dd"); }
    }
    private static YearMonth parseMonth(String value) {
        try { return YearMonth.parse(value); } catch (DateTimeParseException failure) { throw new IllegalArgumentException("date must be yyyy-MM"); }
    }
    private static LocalDateTime parseDateTime(String value) {
        try { return LocalDateTime.parse(value, DATE_TIME); } catch (DateTimeParseException failure) { throw new IllegalArgumentException("at must be yyyy-MM-dd HH:mm:ss"); }
    }
    private static OaAuthenticatedRequestExecutor.RequestKind read() { return OaAuthenticatedRequestExecutor.RequestKind.READ; }
    private static String identifier(Map<String, Object> value) { return value == null ? null : identifier(value.get("id") != null ? value.get("id") : value.get("userId")); }
    private static String userIdentifier(Map<String, Object> value) {
        return value == null ? null : identifier(value.get("userId") != null ? value.get("userId") : value.get("id"));
    }
    private static String identifier(Object value) { return value == null ? null : String.valueOf(value); }
    private static com.wzx.babiq.server.application.auth.TrustedDesktopConnection connection(TrustedBusinessIdentity identity) {
        return new com.wzx.babiq.server.application.auth.TrustedDesktopConnection(identity.reservationId(),
                identity.desktopInstanceId(), identity.desktopSessionId(), identity.webSocketSessionId());
    }
    private static List<Map<String, Object>> safeItems(List<Map<String, Object>> values) {
        if (values == null) return List.of();
        return values.stream().limit(BusinessWorkbenchPayloadLimits.MAX_ITEMS)
                .map(BusinessScheduleService::safeItem).toList();
    }
    private static Map<String, Object> safeItem(Map<String, Object> value) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (value == null) return safe;
        for (String key : List.of("id", "userId", "name", "nickName", "userName", "title", "type",
                "typeName", "color", "code", "roleCode", "roleName", "caseName", "caseNo", "visitItem",
                "visitTime", "serviceTitle", "serviceObjectName", "projectId", "projectName", "recordId",
                "categoryId", "categoryName")) {
            if (value.containsKey(key)) {
                Object bounded = BusinessWorkbenchPayloadLimits.value(value.get(key));
                if (bounded != null) safe.put(key, bounded);
            }
        }
        if (value.get("avatar") instanceof String handle && isOpaqueHandle(handle)) {
            safe.put("avatar", handle);
        }
        if (value.get("projects") instanceof List<?> projects) {
            safe.put("projects", projects.stream().limit(BusinessWorkbenchPayloadLimits.MAX_ITEMS)
                    .filter(Map.class::isInstance)
                    .map(item -> safeProject((Map<?, ?>) item))
                    .toList());
        }
        return safe;
    }

    private static Map<String, Object> safeProject(Map<?, ?> project) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("id", "projectId", "projectName", "recordId", "name", "label", "value")) {
            Object bounded = BusinessWorkbenchPayloadLimits.value(project.get(key));
            if (bounded != null) safe.put(key, bounded);
        }
        if (project.get("projects") instanceof List<?> children) {
            safe.put("projects", children.stream().limit(BusinessWorkbenchPayloadLimits.MAX_ITEMS)
                    .filter(Map.class::isInstance)
                    .map(item -> safeProject((Map<?, ?>) item))
                    .toList());
        }
        return safe;
    }

    private static boolean isOpaqueHandle(String value) {
        return value.length() >= 20 && value.length() <= 128
                && value.chars().allMatch(character -> Character.isLetterOrDigit(character)
                || character == '-' || character == '_');
    }

    private enum CreateState { IN_FLIGHT, COMPLETED, OUTCOME_UNKNOWN }
    private record CreateKey(String authSessionId, String desktopInstanceId, String desktopSessionId,
                             String tenantId, long generation, String clientOperationId) { }
    private record SortRevisionKey(String authSessionId, String tenantId, String userId, String kind) { }
    private record CreateOperation(BusinessWorkbenchDtos.ScheduleCreateRequest request,
                                   CreateState state, BusinessWorkbenchDtos.MutationEnvelope result) { }
    private static final class DurableOperation {
        private final String operationId;
        private final String fingerprint;
        private final Long completedRevision;
        private boolean remotePhaseEntered;

        private DurableOperation(String operationId, String fingerprint, Long completedRevision) {
            this.operationId = operationId;
            this.fingerprint = fingerprint;
            this.completedRevision = completedRevision;
        }
    }
}
