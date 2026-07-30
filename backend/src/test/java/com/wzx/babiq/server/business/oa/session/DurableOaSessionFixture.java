package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.settings.SecretStore;
import com.wzx.babiq.server.settings.SecretStoreException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only assembly for the production durable OA credential lifecycle. */
public final class DurableOaSessionFixture {
    private final OaSessionCredentialStore credentials;
    private final BusinessOaSecretCleanupRepository cleanupRepository;
    private final BusinessOaSecretCleanupService cleanupService;
    private final OaSessionPersistenceService persistence;
    private final BusinessOaSessionRegistry sessions;

    private DurableOaSessionFixture(
            OaSessionRepository repository,
            OaSessionCredentialStore credentials,
            BusinessOaSecretCleanupRepository cleanupRepository,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "cleanupRepository");
        this.cleanupService = new BusinessOaSecretCleanupService(
                cleanupRepository, credentials, transactionManager, clock);
        this.persistence = new OaSessionPersistenceService(
                repository, cleanupRepository, cleanupService, transactionManager, clock);
        this.sessions = new BusinessOaSessionRegistry(repository, persistence);
    }

    public static DurableOaSessionFixture memory(OaSessionRepository repository) {
        return memory(repository, newCredentialStore(), Clock.systemUTC());
    }

    public static DurableOaSessionFixture memory(OaSessionRepository repository, Clock clock) {
        return memory(repository, newCredentialStore(), clock);
    }

    public static DurableOaSessionFixture memory(
            OaSessionRepository repository,
            OaSessionCredentialStore credentials) {
        return memory(repository, credentials, Clock.systemUTC());
    }

    public static DurableOaSessionFixture memory(
            OaSessionRepository repository,
            OaSessionCredentialStore credentials,
            Clock clock) {
        FixtureTransactionManager transactionManager = new FixtureTransactionManager();
        OaSessionRepository transactionalRepository =
                new RollbackAwareOaSessionRepository(
                        Objects.requireNonNull(repository, "repository"),
                        transactionManager);
        InMemoryCleanupRepository cleanupRepository =
                new InMemoryCleanupRepository(transactionManager);
        return new DurableOaSessionFixture(
                transactionalRepository,
                credentials,
                cleanupRepository,
                transactionManager,
                Objects.requireNonNull(clock, "clock"));
    }

    public static OaSessionCredentialStore newCredentialStore() {
        return new OaSessionCredentialStore(new MemorySecretStore());
    }

    /** Seeds one active test credential through the production reserve-before-write journal protocol. */
    public static String seedCredential(
            OaSessionCredentialStore credentials,
            String authSessionId,
            int version,
            char[] accessToken,
            char[] refreshToken) {
        FixtureTransactionManager transactionManager = new FixtureTransactionManager();
        InMemoryCleanupRepository cleanup = new InMemoryCleanupRepository(transactionManager);
        BusinessOaSecretCleanupService service = new BusinessOaSecretCleanupService(
                cleanup,
                Objects.requireNonNull(credentials, "credentials"),
                transactionManager,
                Clock.systemUTC());
        try {
            String secretRef = service.reserveAndWrite(
                    authSessionId,
                    version,
                    accessToken,
                    refreshToken,
                    "TEST_SEED",
                    null);
            if (!cleanup.consumeReserved(secretRef, authSessionId)) {
                throw new IllegalStateException("test credential reservation was not consumed");
            }
            return secretRef;
        } finally {
            Arrays.fill(accessToken, '\0');
            Arrays.fill(refreshToken, '\0');
        }
    }

    public OaSessionCredentialStore credentials() {
        return credentials;
    }

    public BusinessOaSecretCleanupRepository cleanupRepository() {
        return cleanupRepository;
    }

    public BusinessOaSecretCleanupService cleanupService() {
        return cleanupService;
    }

    public OaSessionPersistenceService persistence() {
        return persistence;
    }

    public BusinessOaSessionRegistry sessions() {
        return sessions;
    }

    /** Explicit-reference store required by reserve-before-write lifecycle tests. */
    public static final class MemorySecretStore implements SecretStore {
        private final Map<String, char[]> values = new ConcurrentHashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public String allocateRef(String namespace) {
            return "keystore://" + namespace + "/test-" + sequence.incrementAndGet();
        }

        @Override
        public void saveCharsAtRef(String secretRef, char[] secretChars) {
            char[] previous = values.putIfAbsent(secretRef, secretChars.clone());
            if (previous != null) {
                throw new SecretStoreException(
                        "SECRET_STORE_REFERENCE_EXISTS", "SecretStore reference already exists");
            }
        }

        @Override
        public List<String> listRefs(String namespacePrefix) {
            String prefix = "keystore://" + namespacePrefix;
            return values.keySet().stream()
                    .filter(ref -> ref.startsWith(prefix))
                    .sorted()
                    .toList();
        }

        @Override
        public String save(String namespace, String secretPlainText) {
            String secretRef = allocateRef(namespace);
            saveCharsAtRef(secretRef, secretPlainText.toCharArray());
            return secretRef;
        }

        @Override
        public Optional<String> load(String secretRef) {
            return loadChars(secretRef).map(String::new);
        }

        @Override
        public Optional<char[]> loadChars(String secretRef) {
            char[] value = values.get(secretRef);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public void delete(String secretRef) {
            if (secretRef != null) {
                values.remove(secretRef);
            }
        }

        public int size() {
            return values.size();
        }
    }

    private static final class InMemoryCleanupRepository
            implements BusinessOaSecretCleanupRepository {
        private final Map<String, BusinessOaSecretCleanupRecord> records = new ConcurrentHashMap<>();
        private final FixtureTransactionManager transactionManager;

        private InMemoryCleanupRepository(FixtureTransactionManager transactionManager) {
            this.transactionManager = Objects.requireNonNull(
                    transactionManager, "transactionManager");
        }

        @Override
        public synchronized BusinessOaSecretCleanupRecord upsertReserved(
                String secretRef,
                String authSessionId,
                String reasonCode,
                String operationId,
                Instant now) {
            BusinessOaSecretCleanupRecord existing = records.get(secretRef);
            if (existing != null) {
                if (existing.state() == BusinessOaSecretCleanupState.RESERVED
                        && existing.authSessionId().equals(authSessionId)) {
                    registerRollback(secretRef, existing);
                    BusinessOaSecretCleanupRecord reserved = new BusinessOaSecretCleanupRecord(
                            secretRef,
                            authSessionId,
                            BusinessOaSecretCleanupState.RESERVED,
                            reasonCode,
                            operationId,
                            0,
                            existing.createdAt(),
                            now,
                            null,
                            null);
                    records.put(secretRef, reserved);
                    return reserved;
                }
                throw stateConflict();
            }
            registerRollback(secretRef, null);
            BusinessOaSecretCleanupRecord reserved = new BusinessOaSecretCleanupRecord(
                    secretRef, authSessionId, BusinessOaSecretCleanupState.RESERVED,
                    reasonCode, operationId, 0, now, now, null, null);
            records.put(secretRef, reserved);
            return reserved;
        }

        @Override
        public synchronized boolean consumeReserved(String secretRef, String authSessionId) {
            BusinessOaSecretCleanupRecord current = records.get(secretRef);
            if (current == null
                    || current.state() != BusinessOaSecretCleanupState.RESERVED
                    || !current.authSessionId().equals(authSessionId)) {
                return false;
            }
            registerRollback(secretRef, current);
            return records.remove(secretRef, current);
        }

        @Override
        public synchronized BusinessOaSecretCleanupRecord upsertDeletePending(
                String secretRef,
                String authSessionId,
                String reasonCode,
                String operationId,
                Instant now) {
            BusinessOaSecretCleanupRecord existing = records.get(secretRef);
            if (existing != null && !existing.authSessionId().equals(authSessionId)) {
                throw stateConflict();
            }
            registerRollback(secretRef, existing);
            BusinessOaSecretCleanupRecord pending = new BusinessOaSecretCleanupRecord(
                    secretRef, authSessionId, BusinessOaSecretCleanupState.DELETE_PENDING,
                    reasonCode,
                    operationId,
                    existing == null ? 0 : existing.attemptCount(),
                    existing == null ? now : existing.createdAt(),
                    now,
                    existing == null ? null : existing.lastAttemptAt(),
                    existing == null ? null : existing.lastResultCode());
            records.put(secretRef, pending);
            return pending;
        }

        @Override
        public Optional<BusinessOaSecretCleanupRecord> findBySecretRef(String secretRef) {
            return Optional.ofNullable(records.get(secretRef));
        }

        @Override
        public synchronized boolean markDeletePending(
                String secretRef,
                String reasonCode,
                String operationId,
                Instant now) {
            BusinessOaSecretCleanupRecord current = records.get(secretRef);
            if (current == null || current.state() != BusinessOaSecretCleanupState.RESERVED) {
                return false;
            }
            registerRollback(secretRef, current);
            records.put(secretRef, new BusinessOaSecretCleanupRecord(
                    secretRef, current.authSessionId(), BusinessOaSecretCleanupState.DELETE_PENDING,
                    reasonCode, operationId, current.attemptCount(), current.createdAt(), now,
                    current.lastAttemptAt(), current.lastResultCode()));
            return true;
        }

        @Override
        public synchronized boolean markReservedDeletePending(
                String secretRef,
                String authSessionId,
                String reasonCode,
                String operationId,
                Instant now) {
            BusinessOaSecretCleanupRecord current = records.get(secretRef);
            if (current == null
                    || current.state() != BusinessOaSecretCleanupState.RESERVED
                    || !current.authSessionId().equals(authSessionId)) {
                return false;
            }
            registerRollback(secretRef, current);
            records.put(secretRef, new BusinessOaSecretCleanupRecord(
                    secretRef, current.authSessionId(), BusinessOaSecretCleanupState.DELETE_PENDING,
                    reasonCode, operationId, current.attemptCount(), current.createdAt(), now,
                    current.lastAttemptAt(), current.lastResultCode()));
            return true;
        }

        @Override
        public synchronized boolean recordDeleteFailure(
                String secretRef,
                String resultCode,
                Instant attemptedAt) {
            BusinessOaSecretCleanupRecord current = records.get(secretRef);
            if (current == null || current.state() != BusinessOaSecretCleanupState.DELETE_PENDING) {
                return false;
            }
            registerRollback(secretRef, current);
            records.put(secretRef, new BusinessOaSecretCleanupRecord(
                    secretRef, current.authSessionId(), current.state(), current.reasonCode(),
                    current.operationId(), current.attemptCount() + 1, current.createdAt(),
                    attemptedAt, attemptedAt, resultCode));
            return true;
        }

        @Override
        public List<BusinessOaSecretCleanupRecord> listByState(BusinessOaSecretCleanupState state) {
            return sorted(records.values().stream().filter(record -> record.state() == state).toList());
        }

        @Override
        public List<BusinessOaSecretCleanupRecord> listDeletePendingBatch(int limit) {
            if (limit <= 0) {
                return List.of();
            }
            return sorted(records.values().stream()
                    .filter(record -> record.state() == BusinessOaSecretCleanupState.DELETE_PENDING)
                    .toList()).stream().limit(limit).toList();
        }

        @Override
        public boolean existsByAuthSessionId(String authSessionId) {
            return records.values().stream()
                    .anyMatch(record -> record.authSessionId().equals(authSessionId));
        }

        @Override
        public synchronized boolean deleteTombstone(String secretRef) {
            BusinessOaSecretCleanupRecord current = records.get(secretRef);
            if (current == null
                    || current.state() != BusinessOaSecretCleanupState.DELETE_PENDING) {
                return false;
            }
            registerRollback(secretRef, current);
            return records.remove(secretRef, current);
        }

        private void registerRollback(
                String secretRef,
                BusinessOaSecretCleanupRecord previous) {
            transactionManager.onRollback(() -> {
                synchronized (this) {
                    if (previous == null) {
                        records.remove(secretRef);
                    } else {
                        records.put(secretRef, previous);
                    }
                }
            });
        }

        private static BusinessOaSecretCleanupException stateConflict() {
            return new BusinessOaSecretCleanupException(
                    "SECRET_CLEANUP_STATE_CONFLICT",
                    "OA secret cleanup state conflict");
        }

        private static List<BusinessOaSecretCleanupRecord> sorted(
                List<BusinessOaSecretCleanupRecord> records) {
            return records.stream()
                    .sorted(Comparator.comparing(BusinessOaSecretCleanupRecord::updatedAt)
                            .thenComparing(BusinessOaSecretCleanupRecord::secretRef))
                    .toList();
        }
    }

    /** Adds rollback callbacks around the caller-supplied in-memory session repository. */
    private static final class RollbackAwareOaSessionRepository implements OaSessionRepository {
        private final OaSessionRepository delegate;
        private final FixtureTransactionManager transactionManager;

        private RollbackAwareOaSessionRepository(
                OaSessionRepository delegate,
                FixtureTransactionManager transactionManager) {
            this.delegate = delegate;
            this.transactionManager = transactionManager;
        }

        @Override
        public Optional<OaSessionRecord> findByAuthSessionId(String authSessionId) {
            return delegate.findByAuthSessionId(authSessionId);
        }

        @Override
        public Optional<OaSessionRecord> findByDesktopSession(
                String desktopInstanceId,
                String desktopSessionId) {
            return delegate.findByDesktopSession(desktopInstanceId, desktopSessionId);
        }

        @Override
        public boolean existsCredentialReference(String secretRef) {
            return delegate.existsCredentialReference(secretRef);
        }

        @Override
        public Optional<OaSessionRecord> findLatestDetachedByDesktopInstanceId(
                String desktopInstanceId) {
            return delegate.findLatestDetachedByDesktopInstanceId(desktopInstanceId);
        }

        @Override
        public OaSessionRecord insert(OaSessionRecord record) {
            Optional<OaSessionRecord> previous = delegate.findByAuthSessionId(record.authSessionId());
            OaSessionRecord inserted = delegate.insert(record);
            registerRestore(previous);
            return inserted;
        }

        @Override
        public OaSessionRecord update(OaSessionRecord record) {
            Optional<OaSessionRecord> previous = delegate.findByAuthSessionId(record.authSessionId());
            OaSessionRecord updated = delegate.update(record);
            registerRestore(previous);
            return updated;
        }

        @Override
        public boolean compareAndSwapGeneration(
                String authSessionId,
                long expectedGeneration,
                OaSessionRecord record) {
            Optional<OaSessionRecord> previous = delegate.findByAuthSessionId(authSessionId);
            boolean updated = delegate.compareAndSwapGeneration(
                    authSessionId, expectedGeneration, record);
            if (updated) {
                registerRestore(previous);
            }
            return updated;
        }

        @Override
        public boolean compareAndSwapExact(
                OaSessionRecord expected,
                OaSessionRecord next) {
            boolean updated = delegate.compareAndSwapExact(expected, next);
            if (updated) {
                registerRestore(Optional.of(expected));
            }
            return updated;
        }

        @Override
        public boolean compareAndSwapStage(
                String authSessionId,
                OaSessionPhase expectedSourcePhase,
                long expectedGeneration,
                String expectedDesktopInstanceId,
                String expectedDesktopSessionId,
                String expectedActiveCredentialRef,
                OaSessionRecord record) {
            Optional<OaSessionRecord> previous = delegate.findByAuthSessionId(authSessionId);
            boolean updated = delegate.compareAndSwapStage(
                    authSessionId,
                    expectedSourcePhase,
                    expectedGeneration,
                    expectedDesktopInstanceId,
                    expectedDesktopSessionId,
                    expectedActiveCredentialRef,
                    record);
            if (updated) {
                registerRestore(previous);
            }
            return updated;
        }

        @Override
        public boolean compareAndSwapDetachedLease(
                String authSessionId,
                long expectedGeneration,
                String expectedDesktopInstanceId,
                String expectedDesktopSessionId,
                OaSessionRecord record) {
            Optional<OaSessionRecord> previous = delegate.findByAuthSessionId(authSessionId);
            boolean updated = delegate.compareAndSwapDetachedLease(
                    authSessionId,
                    expectedGeneration,
                    expectedDesktopInstanceId,
                    expectedDesktopSessionId,
                    record);
            if (updated) {
                registerRestore(previous);
            }
            return updated;
        }

        @Override
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
            Optional<OaSessionRecord> previous = delegate.findByAuthSessionId(authSessionId);
            boolean updated = delegate.compareAndSwapInstallation(
                    authSessionId,
                    expectedGeneration,
                    expectedInstallationId,
                    expectedOwnerDesktopInstanceId,
                    expectedOwnerDesktopSessionId,
                    expectedTargetGeneration,
                    expectedActiveCredentialRef,
                    expectedStagedCredentialRef,
                    record);
            if (updated) {
                registerRestore(previous);
            }
            return updated;
        }

        @Override
        public boolean compareAndSwapRecoverySnapshot(
                String authSessionId,
                OaSessionPhase expectedPhase,
                long expectedGeneration,
                String expectedInstallationId,
                String expectedActiveCredentialRef,
                String expectedStagedCredentialRef,
                OaSessionRecord record) {
            Optional<OaSessionRecord> previous = delegate.findByAuthSessionId(authSessionId);
            boolean updated = delegate.compareAndSwapRecoverySnapshot(
                    authSessionId,
                    expectedPhase,
                    expectedGeneration,
                    expectedInstallationId,
                    expectedActiveCredentialRef,
                    expectedStagedCredentialRef,
                    record);
            if (updated) {
                registerRestore(previous);
            }
            return updated;
        }

        @Override
        public List<OaSessionRecord> listRecoverable() {
            return delegate.listRecoverable();
        }

        private void registerRestore(Optional<OaSessionRecord> previous) {
            previous.ifPresent(record -> transactionManager.onRollback(
                    () -> delegate.update(record)));
        }
    }

    /** Minimal transaction manager with real rollback callbacks for the fixture's in-memory state. */
    private static final class FixtureTransactionManager implements PlatformTransactionManager {
        private final ThreadLocal<TransactionFrame> current = new ThreadLocal<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            TransactionFrame parent = current.get();
            boolean transactional = definition == null
                    || definition.getPropagationBehavior()
                    != TransactionDefinition.PROPAGATION_NOT_SUPPORTED;
            TransactionFrame frame = new TransactionFrame(parent, transactional);
            current.set(frame);
            return new FixtureTransactionStatus(frame);
        }

        @Override
        public void commit(TransactionStatus status) {
            FixtureTransactionStatus fixtureStatus = requireStatus(status);
            if (fixtureStatus.isRollbackOnly()) {
                rollback(fixtureStatus);
                return;
            }
            finish(fixtureStatus);
        }

        @Override
        public void rollback(TransactionStatus status) {
            FixtureTransactionStatus fixtureStatus = requireStatus(status);
            RuntimeException rollbackFailure = null;
            try {
                if (fixtureStatus.frame.transactional) {
                    for (int index = fixtureStatus.frame.rollbackActions.size() - 1;
                         index >= 0;
                         index--) {
                        try {
                            fixtureStatus.frame.rollbackActions.get(index).run();
                        } catch (RuntimeException failure) {
                            if (rollbackFailure == null) {
                                rollbackFailure = new IllegalStateException(
                                        "fixture transaction rollback failed", failure);
                            } else {
                                rollbackFailure.addSuppressed(failure);
                            }
                        }
                    }
                }
            } finally {
                finish(fixtureStatus);
            }
            if (rollbackFailure != null) {
                throw rollbackFailure;
            }
        }

        private void onRollback(Runnable action) {
            TransactionFrame frame = current.get();
            if (frame != null && frame.transactional) {
                frame.rollbackActions.add(action);
            }
        }

        private FixtureTransactionStatus requireStatus(TransactionStatus status) {
            if (!(status instanceof FixtureTransactionStatus fixtureStatus)
                    || fixtureStatus.completed) {
                throw new IllegalStateException("invalid fixture transaction status");
            }
            return fixtureStatus;
        }

        private void finish(FixtureTransactionStatus status) {
            if (current.get() != status.frame) {
                throw new IllegalStateException("fixture transaction completion order mismatch");
            }
            status.frame.rollbackActions.clear();
            status.completed = true;
            if (status.frame.parent == null) {
                current.remove();
            } else {
                current.set(status.frame.parent);
            }
        }

        private static final class FixtureTransactionStatus extends SimpleTransactionStatus {
            private final TransactionFrame frame;
            private boolean completed;

            private FixtureTransactionStatus(TransactionFrame frame) {
                super(frame.transactional);
                this.frame = frame;
            }
        }

        private static final class TransactionFrame {
            private final TransactionFrame parent;
            private final boolean transactional;
            private final List<Runnable> rollbackActions = new ArrayList<>();

            private TransactionFrame(TransactionFrame parent, boolean transactional) {
                this.parent = parent;
                this.transactional = transactional;
            }
        }
    }
}
