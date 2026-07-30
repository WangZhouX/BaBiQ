package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.settings.SecretStore;
import com.wzx.babiq.server.settings.SecretStoreException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class OaSessionCredentialStoreTest {

    @Test
    void production_api_exposes_only_preallocated_writes_without_legacy_save_compensation() {
        assertThat(Arrays.stream(OaSessionCredentialStore.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("save");
        assertThat(Arrays.stream(OaSessionCredentialStore.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("saveLegacy", "compensateRejectedLegacySave");
    }

    @Test
    void stores_versioned_access_and_refresh_material_without_plaintext_envelope() {
        MemorySecretStore secrets = new MemorySecretStore();
        OaSessionCredentialStore store = new OaSessionCredentialStore(secrets);

        String ref = writeExplicit(
                store, "auth-1", 7, "access-canary".toCharArray(), "refresh-canary".toCharArray());

        assertThat(ref).startsWith("keystore://");
        assertThat(secrets.values().values()).noneMatch(value -> value.contains("access-canary") || value.contains("refresh-canary"));
        assertThat(secrets.values().values()).noneMatch(value -> value.contains(Base64.getEncoder().encodeToString("access-canary".getBytes()))
                || value.contains(Base64.getEncoder().encodeToString("refresh-canary".getBytes())));
        assertThat(secrets.saveCharsCalls()).isEqualTo(1);
        OaSessionCredentialStore.CredentialMaterial material = store.load(ref);
        try {
            assertThat(material.accessToken()).isEqualTo("access-canary".toCharArray());
            assertThat(material.refreshToken()).isEqualTo("refresh-canary".toCharArray());
            assertThat(material.version()).isEqualTo(7);
        } finally {
            material.close();
        }
    }

    @Test
    void rejects_blank_material_and_deletes_only_the_requested_reference() {
        MemorySecretStore secrets = new MemorySecretStore();
        OaSessionCredentialStore store = new OaSessionCredentialStore(secrets);

        assertThatThrownBy(() -> writeExplicit(
                store, "auth-1", 1, new char[0], "refresh".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
        String first = writeExplicit(store, "auth-1", 1, "a".toCharArray(), "r".toCharArray());
        String second = writeExplicit(store, "auth-1", 2, "a2".toCharArray(), "r2".toCharArray());

        store.delete(first);

        assertThat(store.load(first)).isNull();
        try (var remaining = store.load(second)) {
            assertThat(remaining).isNotNull();
        }
    }

    @Test
    void char_array_secret_api_round_trips_and_wipes_loaded_material_on_close() {
        MemorySecretStore secrets = new MemorySecretStore();
        OaSessionCredentialStore store = new OaSessionCredentialStore(secrets);

        String ref = writeExplicit(
                store, "auth-1", 8, "access-char-api".toCharArray(), "refresh-char-api".toCharArray());

        var material = store.load(ref);
        char[] access = material.accessToken();
        char[] refresh = material.refreshToken();
        assertThat(access).isEqualTo("access-char-api".toCharArray());
        assertThat(refresh).isEqualTo("refresh-char-api".toCharArray());

        material.close();

        assertThat(access).containsOnly('\0');
        assertThat(refresh).containsOnly('\0');
    }

    @Test
    void explicit_reference_api_allocates_writes_and_lists() {
        ExplicitSecretStore secrets = new ExplicitSecretStore();
        OaSessionCredentialStore store = new OaSessionCredentialStore(secrets);

        String reserved = store.allocateRef("auth-explicit");
        store.saveAtRef(reserved, 3, "access-explicit".toCharArray(), "refresh-explicit".toCharArray());
        String delegated = writeExplicit(
                store, "auth-delegated", 4,
                "access-delegated".toCharArray(), "refresh-delegated".toCharArray());
        secrets.putRaw("keystore://provider.deepseek-secret", "provider-secret");
        secrets.putRaw("keystore://business.oa2.auth-similar", "similar-prefix-secret");

        assertThat(secrets.allocatedNamespaces())
                .containsExactly("business.oa.auth-explicit", "business.oa.auth-delegated");
        assertThat(secrets.saveAtRefCalls()).isEqualTo(2);
        assertThat(secrets.saveCharsCalls()).isZero();
        assertThat(store.listBusinessOaRefs()).containsExactlyInAnyOrder(reserved, delegated).isSorted();
        try (var material = store.load(reserved)) {
            assertThat(material.version()).isEqualTo(3);
            assertThat(material.accessToken()).isEqualTo("access-explicit".toCharArray());
            assertThat(material.refreshToken()).isEqualTo("refresh-explicit".toCharArray());
        }
    }

    @Test
    void allocator_cannot_provenance_allowlist_known_out_of_scope_keystore_references() {
        for (String invalidRef : List.of(
                "keystore://provider.deepseek-private-ref",
                "keystore://business.oa2.auth-private-ref")) {
            FixedAllocatorSecretStore secrets = new FixedAllocatorSecretStore(invalidRef);
            OaSessionCredentialStore store = new OaSessionCredentialStore(secrets);

            SecretStoreException failure = catchThrowableOfType(
                    SecretStoreException.class,
                    () -> store.allocateRef("auth-invalid-allocation"));

            assertThat(failure.resultCode()).isEqualTo("SECRET_STORE_REFERENCE_INVALID");
            assertSafeFailure(failure, invalidRef, "private-access", "private-refresh");
            assertThat(secrets.saveAtRefCalls()).isZero();
        }
    }

    @Test
    void encoding_failure_is_fixed_safe_and_never_reaches_secret_store() {
        ExplicitSecretStore secrets = new ExplicitSecretStore();
        OaSessionCredentialStore store = new OaSessionCredentialStore(secrets);
        String ref = store.allocateRef("auth-invalid-utf16");
        char[] invalidRefresh = {'\ud800'};

        SecretStoreException failure = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.saveAtRef(
                        ref, 1, "private-access".toCharArray(), invalidRefresh));

        assertThat(failure.resultCode()).isEqualTo("SECRET_STORE_WRITE_FAILED");
        assertSafeFailure(failure, ref, "private-access", "CharacterCodingException");
        assertThat(secrets.saveAtRefCalls()).isZero();
    }

    @Test
    void malformed_loaded_envelope_is_wiped_and_reported_without_original_cause() {
        byte[] payload = ByteBuffer.allocate(9)
                .putInt(1)
                .putInt(1)
                .put((byte) 'x')
                .array();
        char[] malformedEnvelope = hexEnvelope(payload);
        RetainingLoadSecretStore secrets = new RetainingLoadSecretStore(malformedEnvelope);
        OaSessionCredentialStore store = new OaSessionCredentialStore(secrets);
        String ref = "keystore://business.oa.auth-malformed";

        SecretStoreException failure = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.load(ref));

        assertThat(failure.resultCode()).isEqualTo("SECRET_STORE_READ_FAILED");
        assertSafeFailure(failure, ref, "malformed OA credential envelope");
        assertThat(malformedEnvelope).containsOnly('\0');
    }

    @Test
    void explicit_write_rejects_non_oa_namespaces_before_touching_secret_store() {
        ExplicitSecretStore secrets = new ExplicitSecretStore();
        OaSessionCredentialStore store = new OaSessionCredentialStore(secrets);
        String providerRef = "keystore://provider.deepseek-private-ref";
        String similarPrefixRef = "keystore://business.oa2.auth-private-ref";

        SecretStoreException providerFailure = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.saveAtRef(
                        providerRef, 1, "provider-access".toCharArray(), "provider-refresh".toCharArray()));
        SecretStoreException similarPrefixFailure = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.saveAtRef(
                        similarPrefixRef, 1, "similar-access".toCharArray(), "similar-refresh".toCharArray()));

        assertThat(providerFailure.resultCode()).isEqualTo("SECRET_STORE_REFERENCE_INVALID");
        assertThat(similarPrefixFailure.resultCode()).isEqualTo("SECRET_STORE_REFERENCE_INVALID");
        assertSafeFailure(providerFailure, providerRef, "provider-access", "provider-refresh");
        assertSafeFailure(similarPrefixFailure, similarPrefixRef, "similar-access", "similar-refresh");
        assertThat(secrets.saveAtRefCalls()).isZero();
    }

    @Test
    void duplicate_explicit_reference_is_rejected_without_overwriting_or_leaking_details() {
        ExplicitSecretStore secrets = new ExplicitSecretStore();
        OaSessionCredentialStore store = new OaSessionCredentialStore(secrets);
        String ref = store.allocateRef("auth-duplicate");
        store.saveAtRef(ref, 1, "first-access".toCharArray(), "first-refresh".toCharArray());

        SecretStoreException duplicate = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.saveAtRef(
                        ref, 2, "replacement-access".toCharArray(), "replacement-refresh".toCharArray()));

        assertThat(duplicate.resultCode()).isEqualTo("SECRET_STORE_REFERENCE_EXISTS");
        assertSafeFailure(duplicate, ref, "replacement-access", "replacement-refresh");
        try (var material = store.load(ref)) {
            assertThat(material.version()).isEqualTo(1);
            assertThat(material.accessToken()).isEqualTo("first-access".toCharArray());
        }
    }

    @Test
    void storage_failures_are_rethrown_without_reference_path_plaintext_or_original_cause() {
        String ref = "keystore://business.oa.auth-failure-private-ref";
        SecretStore leakyStore = new SecretStore() {
            @Override
            public String save(String namespace, String secretPlainText) {
                throw new IllegalStateException("legacy leak " + namespace + " " + secretPlainText);
            }

            @Override
            public String allocateRef(String namespace) {
                return ref;
            }

            @Override
            public void saveCharsAtRef(String secretRef, char[] secretChars) {
                throw new IllegalStateException(
                        "write leak " + secretRef + " " + new String(secretChars),
                        new IllegalArgumentException("original cause"));
            }

            @Override
            public List<String> listRefs(String namespacePrefix) {
                throw new IllegalStateException(
                        "list leak C:\\private\\secrets.jceks " + namespacePrefix,
                        new IllegalArgumentException("original cause"));
            }

            @Override public Optional<String> load(String secretRef) { return Optional.empty(); }
            @Override public void delete(String secretRef) { }
        };
        OaSessionCredentialStore store = new OaSessionCredentialStore(leakyStore);
        String allocated = store.allocateRef("auth-failure");

        SecretStoreException writeFailure = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.saveAtRef(
                        allocated, 9, "private-access".toCharArray(), "private-refresh".toCharArray()));
        SecretStoreException listFailure = catchThrowableOfType(
                SecretStoreException.class,
                store::listBusinessOaRefs);

        assertThat(writeFailure.resultCode()).isEqualTo("SECRET_STORE_WRITE_FAILED");
        assertThat(listFailure.resultCode()).isEqualTo("SECRET_STORE_LIST_FAILED");
        assertSafeFailure(writeFailure, ref, "private-access", "private-refresh", "original cause");
        assertSafeFailure(listFailure, ref, "C:\\private\\secrets.jceks", "business.oa.", "original cause");
    }

    @Test
    void load_and_delete_reject_non_oa_references_without_touching_secret_store() {
        BoundaryCountingSecretStore secrets = new BoundaryCountingSecretStore();
        OaSessionCredentialStore store = new OaSessionCredentialStore(secrets);
        String providerRef = "keystore://provider.deepseek-private-ref";
        String similarPrefixRef = "keystore://business.oa2.auth-private-ref";

        SecretStoreException providerLoadFailure = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.load(providerRef));
        SecretStoreException similarLoadFailure = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.load(similarPrefixRef));
        SecretStoreException providerDeleteFailure = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.delete(providerRef));
        SecretStoreException similarDeleteFailure = catchThrowableOfType(
                SecretStoreException.class,
                () -> store.delete(similarPrefixRef));

        assertThat(providerLoadFailure.resultCode()).isEqualTo("SECRET_STORE_REFERENCE_INVALID");
        assertThat(similarLoadFailure.resultCode()).isEqualTo("SECRET_STORE_REFERENCE_INVALID");
        assertThat(providerDeleteFailure.resultCode()).isEqualTo("SECRET_STORE_REFERENCE_INVALID");
        assertThat(similarDeleteFailure.resultCode()).isEqualTo("SECRET_STORE_REFERENCE_INVALID");
        assertSafeFailure(providerLoadFailure, providerRef);
        assertSafeFailure(similarLoadFailure, similarPrefixRef);
        assertSafeFailure(providerDeleteFailure, providerRef);
        assertSafeFailure(similarDeleteFailure, similarPrefixRef);
        assertThat(secrets.loadCalls()).isZero();
        assertThat(secrets.deleteCalls()).isZero();
    }

    @Test
    void restarted_store_can_load_and_delete_durable_business_oa_keystore_reference() {
        MemorySecretStore secrets = new MemorySecretStore();
        OaSessionCredentialStore firstProcess = new OaSessionCredentialStore(secrets);
        String ref = writeExplicit(
                firstProcess, "auth-restart", 6,
                "restart-access".toCharArray(), "restart-refresh".toCharArray());
        OaSessionCredentialStore restarted = new OaSessionCredentialStore(secrets);

        try (var material = restarted.load(ref)) {
            assertThat(material.version()).isEqualTo(6);
            assertThat(material.accessToken()).isEqualTo("restart-access".toCharArray());
        }
        restarted.delete(ref);

        assertThat(secrets.load(ref)).isEmpty();
    }

    private static String writeExplicit(
            OaSessionCredentialStore store,
            String authSessionId,
            int version,
            char[] accessToken,
            char[] refreshToken) {
        String secretRef = store.allocateRef(authSessionId);
        store.saveAtRef(secretRef, version, accessToken, refreshToken);
        return secretRef;
    }

    static final class MemorySecretStore implements SecretStore {
        private final Map<String, String> values = new HashMap<>();
        private int allocatedRefs;
        private int saveCharsCalls;

        @Override
        public String save(String namespace, String secretPlainText) {
            String ref = "keystore://" + namespace + "/" + values.size();
            values.put(ref, secretPlainText);
            return ref;
        }

        @Override
        public String allocateRef(String namespace) {
            allocatedRefs++;
            return "keystore://" + namespace + "/" + allocatedRefs;
        }

        @Override
        public void saveCharsAtRef(String secretRef, char[] secretChars) {
            saveCharsCalls++;
            if (values.putIfAbsent(secretRef, new String(secretChars)) != null) {
                throw new SecretStoreException(
                        "SECRET_STORE_REFERENCE_EXISTS", "SecretStore 引用已存在");
            }
        }

        @Override
        public String saveChars(String namespace, char[] secretChars) {
            saveCharsCalls++;
            return save(namespace, new String(secretChars));
        }

        @Override
        public Optional<String> load(String secretRef) {
            return Optional.ofNullable(values.get(secretRef));
        }

        @Override
        public void delete(String secretRef) {
            values.remove(secretRef);
        }

        Map<String, String> values() {
            return values;
        }

        int saveCharsCalls() {
            return saveCharsCalls;
        }
    }

    private static final class ExplicitSecretStore implements SecretStore {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final List<String> allocatedNamespaces = new ArrayList<>();
        private int saveCharsCalls;
        private int saveAtRefCalls;

        @Override
        public String save(String namespace, String secretPlainText) {
            saveCharsCalls++;
            String ref = "legacy://" + namespace + "/" + values.size();
            values.put(ref, secretPlainText);
            return ref;
        }

        @Override
        public String saveChars(String namespace, char[] secretChars) {
            saveCharsCalls++;
            String ref = "legacy://" + namespace + "/" + values.size();
            values.put(ref, new String(secretChars));
            return ref;
        }

        @Override
        public String allocateRef(String namespace) {
            allocatedNamespaces.add(namespace);
            return "memory://" + namespace + "/" + allocatedNamespaces.size();
        }

        @Override
        public void saveCharsAtRef(String secretRef, char[] secretChars) {
            saveAtRefCalls++;
            if (values.containsKey(secretRef)) {
                throw new SecretStoreException(
                        "SECRET_STORE_REFERENCE_EXISTS", "SecretStore 引用已存在");
            }
            values.put(secretRef, new String(secretChars));
        }

        @Override
        public List<String> listRefs(String namespacePrefix) {
            return values.keySet().stream().sorted().toList();
        }

        @Override
        public Optional<String> load(String secretRef) {
            return Optional.ofNullable(values.get(secretRef));
        }

        @Override
        public void delete(String secretRef) {
            values.remove(secretRef);
        }

        void putRaw(String secretRef, String value) {
            values.put(secretRef, value);
        }

        List<String> allocatedNamespaces() {
            return List.copyOf(allocatedNamespaces);
        }

        int saveCharsCalls() {
            return saveCharsCalls;
        }

        int saveAtRefCalls() {
            return saveAtRefCalls;
        }
    }

    private static final class BoundaryCountingSecretStore implements SecretStore {
        private int loadCalls;
        private int deleteCalls;

        @Override public String save(String namespace, String secretPlainText) { return "unused"; }

        @Override
        public Optional<String> load(String secretRef) {
            loadCalls++;
            return Optional.empty();
        }

        @Override
        public void delete(String secretRef) {
            deleteCalls++;
        }

        int loadCalls() {
            return loadCalls;
        }

        int deleteCalls() {
            return deleteCalls;
        }
    }

    private static final class FixedAllocatorSecretStore implements SecretStore {
        private final String ref;
        private int saveAtRefCalls;

        private FixedAllocatorSecretStore(String ref) {
            this.ref = ref;
        }

        @Override public String save(String namespace, String secretPlainText) { return "unused"; }
        @Override public String allocateRef(String namespace) { return ref; }

        @Override
        public void saveCharsAtRef(String secretRef, char[] secretChars) {
            saveAtRefCalls++;
        }

        @Override public Optional<String> load(String secretRef) { return Optional.empty(); }
        @Override public void delete(String secretRef) { }

        int saveAtRefCalls() {
            return saveAtRefCalls;
        }
    }

    private static final class RetainingLoadSecretStore implements SecretStore {
        private final char[] envelope;

        private RetainingLoadSecretStore(char[] envelope) {
            this.envelope = envelope;
        }

        @Override public String save(String namespace, String secretPlainText) { return "unused"; }
        @Override public Optional<String> load(String secretRef) { return Optional.empty(); }
        @Override public Optional<char[]> loadChars(String secretRef) { return Optional.of(envelope); }
        @Override public void delete(String secretRef) { }
    }

    private static char[] hexEnvelope(byte[] payload) {
        char[] magic = "OA-CRED1".toCharArray();
        char[] envelope = new char[magic.length + payload.length * 2];
        System.arraycopy(magic, 0, envelope, 0, magic.length);
        int offset = magic.length;
        for (byte value : payload) {
            int unsigned = value & 0xff;
            envelope[offset++] = Character.forDigit(unsigned >>> 4, 16);
            envelope[offset++] = Character.forDigit(unsigned & 0x0f, 16);
        }
        return envelope;
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
