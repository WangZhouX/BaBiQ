package com.wzx.babiq.server.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.security.auth.DestroyFailedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 本地 KeyStore SecretStore 测试。
 *
 * <p>P2-3 要求 Provider 表只能保存 secretRef，因此这里先用真实临时文件验证：
 * 明文 API Key 能写入 KeyStore、能跨实例读取、删除后不能再读到。</p>
 */
class LocalKeyStoreSecretStoreTest {

    /** 每个测试独立目录，避免不同测试之间共享本地密钥文件。 */
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("KeyStore SecretStore 能保存、重启后读取并删除密钥")
    void keystore_secret_store_should_persist_load_and_delete_secret() {
        Path storePath = tempDir.resolve("babiq-secrets.jceks");
        char[] password = "test-store-password".toCharArray();

        LocalKeyStoreSecretStore firstStore = new LocalKeyStoreSecretStore(storePath, password);
        String secretRef = firstStore.save("provider.deepseek", "sk-local-secret");

        assertThat(secretRef).startsWith("keystore://");
        assertThat(firstStore.load(secretRef)).contains("sk-local-secret");

        LocalKeyStoreSecretStore reopenedStore = new LocalKeyStoreSecretStore(storePath, password);
        assertThat(reopenedStore.load(secretRef)).contains("sk-local-secret");

        reopenedStore.delete(secretRef);
        assertThat(reopenedStore.load(secretRef)).isEmpty();
    }

    @Test
    @DisplayName("SecretStore 读不到密钥时给出明确异常")
    void keystore_secret_store_should_reject_unknown_secret_ref_when_required() {
        LocalKeyStoreSecretStore secretStore = new LocalKeyStoreSecretStore(
                tempDir.resolve("missing-secret.jceks"),
                "test-store-password".toCharArray());

        assertThat(secretStore.load("keystore://missing")).isEmpty();
        assertThatThrownBy(() -> secretStore.require("keystore://missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密钥不存在");
    }

    @Test
    @DisplayName("保存新密钥的文件提交失败时原 KeyStore 文件和旧 entry 保持不变")
    void failed_save_keeps_original_keystore_file_and_existing_entries() throws Exception {
        Path storePath = tempDir.resolve("save-failure.jceks");
        char[] password = "test-store-password".toCharArray();
        LocalKeyStoreSecretStore stableStore = new LocalKeyStoreSecretStore(storePath, password);
        String existingRef = stableStore.save("provider.existing", "sk-existing");
        byte[] originalFile = Files.readAllBytes(storePath);
        LocalKeyStoreSecretStore failingStore = new LocalKeyStoreSecretStore(
                storePath, password, controlledCommitFailure());

        assertThatThrownBy(() -> failingStore.save("provider.new", "sk-new"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写入本地 KeyStore 密钥失败");

        assertThat(Files.readAllBytes(storePath)).containsExactly(originalFile);
        LocalKeyStoreSecretStore reopened = new LocalKeyStoreSecretStore(storePath, password);
        assertThat(reopened.load(existingRef)).contains("sk-existing");
        assertNoTemporaryStoreFiles();
    }

    @Test
    @DisplayName("删除密钥的文件提交失败时原 KeyStore 文件和原 entry 保持不变")
    void failed_delete_keeps_original_keystore_file_and_entry() throws Exception {
        Path storePath = tempDir.resolve("delete-failure.jceks");
        char[] password = "test-store-password".toCharArray();
        LocalKeyStoreSecretStore stableStore = new LocalKeyStoreSecretStore(storePath, password);
        String existingRef = stableStore.save("provider.existing", "sk-existing");
        byte[] originalFile = Files.readAllBytes(storePath);
        LocalKeyStoreSecretStore failingStore = new LocalKeyStoreSecretStore(
                storePath, password, controlledCommitFailure());

        assertThatThrownBy(() -> failingStore.delete(existingRef))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("删除本地 KeyStore 密钥失败");

        assertThat(Files.readAllBytes(storePath)).containsExactly(originalFile);
        LocalKeyStoreSecretStore reopened = new LocalKeyStoreSecretStore(storePath, password);
        assertThat(reopened.load(existingRef)).contains("sk-existing");
        assertNoTemporaryStoreFiles();
    }

    @Test
    @DisplayName("char[] API 能够读写凭据并在调用方关闭后擦除返回数组")
    void char_array_api_should_round_trip_secret() {
        LocalKeyStoreSecretStore store = new LocalKeyStoreSecretStore(
                tempDir.resolve("char-api.jceks"), "test-store-password".toCharArray());
        String ref = store.saveChars("business.oa", "oa-secret".toCharArray());

        char[] loaded = store.requireChars(ref);
        try {
            assertThat(loaded).isEqualTo("oa-secret".toCharArray());
        } finally {
            java.util.Arrays.fill(loaded, '\0');
        }
        char[] loadedAgain = store.loadChars(ref).orElseThrow();
        try {
            assertThat(loadedAgain).isEqualTo("oa-secret".toCharArray());
        } finally {
            java.util.Arrays.fill(loadedAgain, '\0');
        }
        assertThat(loadedAgain).containsOnly('\0');
    }

    @Test
    @DisplayName("预分配引用支持显式写入，重复引用拒绝且不覆盖原 entry")
    void explicit_reference_should_round_trip_and_reject_overwrite() {
        LocalKeyStoreSecretStore store = new LocalKeyStoreSecretStore(
                tempDir.resolve("explicit-ref.jceks"), "test-store-password".toCharArray());
        String secretRef = store.allocateRef("business.oa.auth-explicit");

        store.saveCharsAtRef(secretRef, "first-secret".toCharArray());
        SecretStoreException duplicate = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.saveCharsAtRef(secretRef, "replacement-secret".toCharArray()));

        assertThat(duplicate.resultCode()).isEqualTo("SECRET_STORE_REFERENCE_EXISTS");
        assertSafeFailure(duplicate, secretRef, "explicit-ref.jceks", "replacement-secret");
        assertThat(store.load(secretRef)).contains("first-secret");
    }

    @Test
    @DisplayName("显式引用写入提交失败时原文件和全部已有 entry 保持不变")
    void failed_explicit_reference_commit_keeps_original_file_and_entries() throws Exception {
        Path storePath = tempDir.resolve("explicit-commit-failure.jceks");
        char[] password = "test-store-password".toCharArray();
        LocalKeyStoreSecretStore stableStore = new LocalKeyStoreSecretStore(storePath, password);
        String existingRef = stableStore.save("provider.existing", "sk-existing");
        byte[] originalFile = Files.readAllBytes(storePath);
        LocalKeyStoreSecretStore failingStore = new LocalKeyStoreSecretStore(
                storePath, password, controlledCommitFailure());
        String reservedRef = failingStore.allocateRef("business.oa.auth-failure");

        SecretStoreException failure = catchThrowableOfType(
                SecretStoreException.class,
                () -> failingStore.saveCharsAtRef(reservedRef, "oa-secret".toCharArray()));

        assertThat(failure.resultCode()).isEqualTo("SECRET_STORE_WRITE_FAILED");
        assertSafeFailure(failure, reservedRef, storePath.toString(), "oa-secret");
        assertThat(Files.readAllBytes(storePath)).containsExactly(originalFile);
        LocalKeyStoreSecretStore reopened = new LocalKeyStoreSecretStore(storePath, password);
        assertThat(reopened.load(existingRef)).contains("sk-existing");
        assertThat(reopened.load(reservedRef)).isEmpty();
        assertNoTemporaryStoreFiles();
    }

    @Test
    @DisplayName("命名空间列表稳定排序且只返回 business.oa. 前缀引用")
    void namespace_listing_should_be_stable_and_strictly_scoped() {
        LocalKeyStoreSecretStore store = new LocalKeyStoreSecretStore(
                tempDir.resolve("scoped-list.jceks"), "test-store-password".toCharArray());
        String firstOa = store.saveChars("business.oa.auth-1", "oa-1".toCharArray());
        String secondOa = store.saveChars("business.oa.auth-2", "oa-2".toCharArray());
        String provider = store.saveChars("provider.deepseek", "provider".toCharArray());
        String similarPrefix = store.saveChars("business.oa2.auth-3", "similar".toCharArray());
        String missingDot = store.saveChars("business.oa", "missing-dot".toCharArray());

        List<String> refs = store.listRefs("business.oa.");

        assertThat(refs).containsExactlyInAnyOrder(firstOa, secondOa).isSorted();
        assertThat(refs).doesNotContain(provider, similarPrefix, missingDot);
    }

    @Test
    @DisplayName("混合大小写 namespace 分配为规范小写引用，重启后枚举身份保持完全一致")
    void allocated_reference_is_canonical_and_keeps_exact_identity_across_reopen() {
        Path storePath = tempDir.resolve("canonical-alias.jceks");
        char[] password = "test-store-password".toCharArray();
        LocalKeyStoreSecretStore first = new LocalKeyStoreSecretStore(storePath, password);

        String ref = first.allocateRef("Business.OA.Auth-Mixed");
        first.saveCharsAtRef(ref, "mixed-secret".toCharArray());

        assertThat(ref).isEqualTo(ref.toLowerCase(Locale.ROOT));
        LocalKeyStoreSecretStore reopened = new LocalKeyStoreSecretStore(storePath, password);
        assertThat(reopened.listRefs("Business.OA.")).containsExactly(ref);
        assertThat(reopened.load(ref)).contains("mixed-secret");

        reopened.delete(ref);
        assertThat(reopened.load(ref)).isEmpty();
        assertThat(reopened.listRefs("business.oa.")).isEmpty();
    }

    @Test
    @DisplayName("显式写入拒绝非规范大小写 KeyStore 引用")
    void explicit_write_rejects_noncanonical_mixed_case_reference() {
        LocalKeyStoreSecretStore store = new LocalKeyStoreSecretStore(
                tempDir.resolve("noncanonical-alias.jceks"),
                "test-store-password".toCharArray());
        String mixedCaseRef = "keystore://Business.OA.Auth-Mixed-explicit";

        SecretStoreException failure = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.saveCharsAtRef(mixedCaseRef, "private-secret".toCharArray()));

        assertThat(failure.resultCode()).isEqualTo("SECRET_STORE_REFERENCE_INVALID");
        assertSafeFailure(failure, mixedCaseRef, "private-secret");
        assertThat(store.load(mixedCaseRef)).isEmpty();
        assertThat(store.listRefs("business.oa.")).isEmpty();
    }

    @Test
    @DisplayName("char[] 写入遇到非法 UTF-16 时 fail closed 且不创建 entry")
    void explicit_char_write_rejects_malformed_utf16_without_creating_entry() {
        Path storePath = tempDir.resolve("invalid-utf16.jceks");
        LocalKeyStoreSecretStore store = new LocalKeyStoreSecretStore(
                storePath,
                "test-store-password".toCharArray());
        String secretRef = store.allocateRef("business.oa.auth-invalid-utf16");
        char[] malformed = {'\ud800'};

        try {
            SecretStoreException failure = catchThrowableOfType(
                    SecretStoreException.class,
                    () -> store.saveCharsAtRef(secretRef, malformed));

            assertThat(failure).isNotNull();
            assertThat(failure.resultCode()).isEqualTo("SECRET_STORE_WRITE_FAILED");
            assertSafeFailure(failure, secretRef, storePath.toString(), "CharacterCodingException");
            assertThat(store.load(secretRef)).isEmpty();
        } finally {
            java.util.Arrays.fill(malformed, '\0');
        }
    }

    @Test
    @DisplayName("每次 KeyStore entry 写入和读取后都销毁独立 PasswordProtection")
    void password_protection_is_destroyed_after_each_entry_write_and_read() {
        List<KeyStore.PasswordProtection> protections = new ArrayList<>();
        LocalKeyStoreSecretStore store = new LocalKeyStoreSecretStore(
                tempDir.resolve("destroy-protection.jceks"),
                "test-store-password".toCharArray(),
                password -> {
                    KeyStore.PasswordProtection protection = new KeyStore.PasswordProtection(password);
                    protections.add(protection);
                    return protection;
                });

        String secretRef = store.saveChars("business.oa.destroy", "oa-secret".toCharArray());
        char[] loaded = store.requireChars(secretRef);
        try {
            assertThat(loaded).isEqualTo("oa-secret".toCharArray());
        } finally {
            java.util.Arrays.fill(loaded, '\0');
        }

        assertThat(protections).hasSize(2).allMatch(KeyStore.PasswordProtection::isDestroyed);
    }

    @Test
    @DisplayName("PasswordProtection 销毁失败时写入 fail closed 且不提交 KeyStore 文件")
    void password_protection_destroy_failure_prevents_store_commit_and_is_fixed_safe() {
        Path storePath = tempDir.resolve("destroy-failure.jceks");
        String secretRef = "keystore://business.oa.destroy-failure";
        LocalKeyStoreSecretStore store = new LocalKeyStoreSecretStore(
                storePath,
                "test-store-password".toCharArray(),
                FailingDestroyPasswordProtection::new);

        SecretStoreException failure = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.saveCharsAtRef(secretRef, "private-secret".toCharArray()));

        assertThat(failure).isNotNull();
        assertThat(failure.resultCode()).isEqualTo("SECRET_STORE_WRITE_FAILED");
        assertSafeFailure(failure, secretRef, storePath.toString(), "destroy-private-canary");
        LocalKeyStoreSecretStore reopened = new LocalKeyStoreSecretStore(
                storePath,
                "test-store-password".toCharArray());
        assertThat(reopened.load(secretRef)).isEmpty();
    }

    @Test
    @DisplayName("PasswordProtection 销毁失败时读取 fail closed 且不泄漏密钥")
    void password_protection_destroy_failure_prevents_secret_read_and_is_fixed_safe() {
        Path storePath = tempDir.resolve("read-destroy-failure.jceks");
        char[] password = "test-store-password".toCharArray();
        String secretRef = "keystore://business.oa.read-destroy-failure";
        LocalKeyStoreSecretStore stableStore = new LocalKeyStoreSecretStore(storePath, password);
        stableStore.saveCharsAtRef(secretRef, "private-secret".toCharArray());
        LocalKeyStoreSecretStore failingReadStore = new LocalKeyStoreSecretStore(
                storePath,
                password,
                FailingDestroyPasswordProtection::new);

        SecretStoreException failure = catchThrowableOfType(
                SecretStoreException.class,
                () -> failingReadStore.loadChars(secretRef));

        assertThat(failure).isNotNull();
        assertThat(failure.resultCode()).isEqualTo("SECRET_STORE_READ_FAILED");
        assertSafeFailure(
                failure,
                secretRef,
                storePath.toString(),
                "private-secret",
                "destroy-private-canary");
        assertThat(stableStore.load(secretRef)).contains("private-secret");
    }

    @Test
    @DisplayName("entry 主异常和销毁异常同时发生时保留主异常且不挂 suppressed")
    void entry_failure_remains_primary_when_password_protection_destroy_also_fails() {
        PrimaryAndDestroyFailureProtection[] retained = new PrimaryAndDestroyFailureProtection[1];
        LocalKeyStoreSecretStore store = new LocalKeyStoreSecretStore(
                tempDir.resolve("primary-destroy-failure.jceks"),
                "test-store-password".toCharArray(),
                password -> retained[0] = new PrimaryAndDestroyFailureProtection(password));

        SecretStoreException failure = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.saveCharsAtRef(
                        "keystore://business.oa.primary-destroy-failure",
                        "private-secret".toCharArray()));

        assertThat(failure.resultCode()).isEqualTo("PRIMARY_ENTRY_FAILURE");
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).doesNotContain("destroy-private-canary");
        assertThat(retained[0].destroyCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("business profile 不允许缺少或使用默认 JCEKS 密码")
    void business_profile_should_fail_closed_for_missing_or_default_password() {
        assertThatThrownBy(() -> LocalKeyStoreSecretStore.forBusinessProfile(
                tempDir.resolve("missing-password.jceks"), new char[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalKeyStoreSecretStore.forBusinessProfile(
                tempDir.resolve("default-password.jceks"), "babiq-local-secret-store".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 构造发生在目标文件替换前的可控提交失败。 */
    private static LocalKeyStoreSecretStore.StoreFileCommitter controlledCommitFailure() {
        return (temporaryPath, targetPath) -> {
            throw new IOException("controlled-store-commit-failure");
        };
    }

    private static final class FailingDestroyPasswordProtection extends KeyStore.PasswordProtection {
        private FailingDestroyPasswordProtection(char[] password) {
            super(password);
        }

        @Override
        public synchronized void destroy() throws DestroyFailedException {
            throw new DestroyFailedException("destroy-private-canary");
        }
    }

    private static final class PrimaryAndDestroyFailureProtection extends KeyStore.PasswordProtection {
        private int destroyCalls;

        private PrimaryAndDestroyFailureProtection(char[] password) {
            super(password);
        }

        @Override
        public synchronized char[] getPassword() {
            throw new SecretStoreException("PRIMARY_ENTRY_FAILURE", "entry operation failed");
        }

        @Override
        public synchronized void destroy() throws DestroyFailedException {
            destroyCalls++;
            throw new DestroyFailedException("destroy-private-canary");
        }

        int destroyCalls() {
            return destroyCalls;
        }
    }

    /** 原子提交失败后不得遗留本地临时 KeyStore 文件。 */
    private void assertNoTemporaryStoreFiles() throws IOException {
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    private static void assertSafeFailure(Throwable failure, String... forbiddenValues) {
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        String rendered = failure.getClass().getName() + ":" + failure.getMessage();
        for (String forbidden : forbiddenValues) {
            assertThat(rendered).doesNotContain(forbidden);
        }
    }
}
