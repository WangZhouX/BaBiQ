package com.wzx.babiq.server.business.oa.session;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * OA SecretStore 引用的耐久预留与删除重试边界。
 *
 * <p>数据库状态使用独立短事务提交；JCEKS I/O 始终在事务外执行，避免文件系统操作
 * 扩大 SQLite 锁范围，也保证写入前的 RESERVED tombstone 已经可被恢复流程发现。</p>
 */
@Service
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public class BusinessOaSecretCleanupService {
    private static final String DELETE_FAILURE_CODE = "SECRET_STORE_DELETE_FAILED";
    private static final int DELETE_PENDING_BATCH_SIZE = 100;

    private final BusinessOaSecretCleanupRepository repository;
    private final OaSessionCredentialStore credentialStore;
    private final TransactionTemplate withoutTransaction;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    @Autowired
    public BusinessOaSecretCleanupService(
            BusinessOaSecretCleanupRepository repository,
            OaSessionCredentialStore credentialStore,
            PlatformTransactionManager transactionManager) {
        this(repository, credentialStore, transactionManager, Clock.systemUTC());
    }

    BusinessOaSecretCleanupService(
            BusinessOaSecretCleanupRepository repository,
            OaSessionCredentialStore credentialStore,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 预分配引用，先耐久提交 RESERVED，再在事务外写入 token envelope。
     *
     * <p>写入成功后保留 RESERVED；后续 session ref 关联必须在自己的数据库事务中消费该 tombstone。</p>
     */
    public String reserveAndWrite(
            String authSessionId,
            int version,
            char[] accessToken,
            char[] refreshToken,
            String reasonCode,
            String operationId) {
        try {
            return requireResult(withoutTransaction.execute(ignored -> {
                String secretRef = credentialStore.allocateRef(authSessionId);
                Instant reservedAt = clock.instant();
                requiresNew.executeWithoutResult(status -> repository.upsertReserved(
                        secretRef, authSessionId, reasonCode, operationId, reservedAt));
                try {
                    credentialStore.saveAtRef(secretRef, version, accessToken, refreshToken);
                } catch (RuntimeException failure) {
                    compensateFailedWrite(secretRef, authSessionId, reasonCode, operationId);
                    throw failure;
                }
                return secretRef;
            }));
        } catch (TransactionException ignored) {
            throw transactionFailure();
        }
    }

    /** 同 owner 可把 RESERVED 转为 DELETE_PENDING，或重建缺失 tombstone；跨 owner 冲突拒绝。 */
    public boolean scheduleReservedDelete(
            String secretRef,
            String authSessionId,
            String reasonCode,
            String operationId) {
        try {
            BusinessOaSecretCleanupRecord scheduled = requireResult(
                    withoutTransaction.execute(ignored -> requiresNew.execute(status ->
                            repository.upsertDeletePending(
                                    secretRef,
                                    authSessionId,
                                    reasonCode,
                                    operationId,
                                    clock.instant()))));
            return scheduled.state() == BusinessOaSecretCleanupState.DELETE_PENDING;
        } catch (TransactionException ignored) {
            throw transactionFailure();
        }
    }

    private void compensateFailedWrite(
            String secretRef,
            String authSessionId,
            String reasonCode,
            String operationId) {
        try {
            scheduleReservedDelete(secretRef, authSessionId, reasonCode, operationId);
        } catch (RuntimeException ignored) {
            return;
        }
        try {
            drainDeletePending();
        } catch (RuntimeException ignored) {
            // The durable DELETE_PENDING record remains available for a later drain.
        }
    }

    /**
     * 尝试清理当前单批 DELETE_PENDING 引用。
     *
     * <p>单个 KeyStore 删除失败只记录固定结果码并继续扫描；成功删除（含 alias 已不存在）后
     * 再以独立事务移除 tombstone。</p>
     */
    public DrainReport drainDeletePending() {
        try {
            return requireResult(withoutTransaction.execute(ignored -> drainOutsideTransaction()));
        } catch (TransactionException ignored) {
            throw transactionFailure();
        }
    }

    /**
     * Deletes only the supplied released references and fails closed until every one is gone.
     * This bypasses the bounded background batch so an older backlog cannot hide the credential
     * for the session whose signed-out notification is about to be emitted.
     */
    public void drainDeletePendingStrict(List<String> secretRefs) {
        List<String> targets = secretRefs == null ? List.of() : secretRefs.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        try {
            withoutTransaction.executeWithoutResult(ignored -> drainStrictOutsideTransaction(targets));
        } catch (BusinessOaSecretCleanupException failure) {
            throw failure;
        } catch (TransactionException ignored) {
            throw transactionFailure();
        } catch (RuntimeException ignored) {
            throw strictIncomplete();
        }
    }

    private void drainStrictOutsideTransaction(List<String> targets) {
        for (String secretRef : targets) {
            BusinessOaSecretCleanupRecord pending = requiresNew.execute(
                    status -> repository.findBySecretRef(secretRef).orElse(null));
            if (pending == null) continue;
            if (pending.state() != BusinessOaSecretCleanupState.DELETE_PENDING
                    || !tryDelete(secretRef)) {
                throw strictIncomplete();
            }
            Boolean finalized = requiresNew.execute(
                    status -> repository.deleteTombstone(secretRef));
            if (!Boolean.TRUE.equals(finalized)) {
                throw strictIncomplete();
            }
        }
    }

    private DrainReport drainOutsideTransaction() {
        List<BusinessOaSecretCleanupRecord> pending = requireResult(requiresNew.execute(status ->
                repository.listDeletePendingBatch(DELETE_PENDING_BATCH_SIZE)));
        int deleted = 0;
        int failed = 0;
        int concurrent = 0;
        for (BusinessOaSecretCleanupRecord record : pending) {
            try {
                if (!tryDelete(record.secretRef())) {
                    failed++;
                    continue;
                }
                Boolean finalized = requiresNew.execute(
                        status -> repository.deleteTombstone(record.secretRef()));
                if (Boolean.TRUE.equals(finalized)) {
                    deleted++;
                } else {
                    concurrent++;
                }
            } catch (RuntimeException ignored) {
                failed++;
            }
        }
        return new DrainReport(pending.size(), deleted, failed, concurrent);
    }

    private boolean tryDelete(String secretRef) {
        try {
            credentialStore.delete(secretRef);
            return true;
        } catch (RuntimeException ignored) {
            Instant attemptedAt = clock.instant();
            requiresNew.executeWithoutResult(status -> repository.recordDeleteFailure(
                    secretRef, DELETE_FAILURE_CODE, attemptedAt));
            return false;
        }
    }

    private static <T> T requireResult(T value) {
        if (value == null) throw new IllegalStateException("事务执行未返回结果");
        return value;
    }

    private static BusinessOaSecretCleanupException transactionFailure() {
        return new BusinessOaSecretCleanupException(
                "SECRET_CLEANUP_TRANSACTION_FAILED",
                "OA 密钥清理事务执行失败");
    }

    /** 不含 secretRef 的单次 drain 汇总。 */
    private static BusinessOaSecretCleanupException strictIncomplete() {
        return new BusinessOaSecretCleanupException(
                "SECRET_CLEANUP_INCOMPLETE",
                "OA credential cleanup is incomplete");
    }

    public record DrainReport(int scanned, int deleted, int failed, int concurrent) {
        public DrainReport {
            if (scanned < 0 || deleted < 0 || failed < 0 || concurrent < 0
                    || (long) deleted + failed + concurrent != scanned) {
                throw new IllegalArgumentException("清理汇总计数无效");
            }
        }
    }
}
