package com.wzx.babiq.server.business;

import com.wzx.babiq.server.business.api.BusinessAuthProtocolHandler;
import com.wzx.babiq.server.business.identity.BusinessOaReadyInstaller;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.business.oa.client.OaWorkbenchGateway;
import com.wzx.babiq.server.business.oa.session.BusinessOaAuthenticationService;
import com.wzx.babiq.server.business.oa.session.OaAuthenticatedRequestExecutor;
import com.wzx.babiq.server.business.oa.session.OaSessionCredentialStore;
import com.wzx.babiq.server.business.oa.session.OaSessionPersistenceService;
import com.wzx.babiq.server.business.oa.session.OaTokenRefreshCoordinator;
import com.wzx.babiq.server.business.workbench.BusinessDataScopeValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("business-desktop")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "huitai.oa.base-url=http://127.0.0.1:48080",
        "babiq.secrets.keystore-password=bean-wiring-test-password"
})
class BusinessOaBeanWiringIT {
    @TempDir(cleanup = CleanupMode.ALWAYS)
    static Path RUNTIME;

    @DynamicPropertySource
    static void runtime(DynamicPropertyRegistry registry) throws Exception {
        Files.writeString(RUNTIME.resolve("session-token"),
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        registry.add("babiq.business.runtime-dir", RUNTIME::toString);
        registry.add("babiq.persistence.database-path", () -> RUNTIME.resolve("db.sqlite").toString());
        registry.add("babiq.secrets.keystore-path", () -> RUNTIME.resolve("secrets.jceks").toString());
        registry.add("babiq.business.session-token-file", () -> RUNTIME.resolve("session-token").toString());
    }

    @Autowired private BusinessAuthProtocolHandler authHandler;
    @Autowired private BusinessOaReadyInstaller readyInstaller;
    @Autowired private BusinessOaAuthenticationService authenticationService;
    @Autowired private OaSessionCredentialStore credentialStore;
    @Autowired private OaSessionPersistenceService persistenceService;
    @Autowired private OaTokenRefreshCoordinator refreshCoordinator;
    @Autowired private OaAuthenticatedRequestExecutor requestExecutor;
    @Autowired private OaAuthenticationGateway authenticationGateway;
    @Autowired private OaWorkbenchGateway workbenchGateway;
    @Autowired private BusinessDataScopeValidator dataScopeValidator;

    @Test
    void exposes_server_owned_oa_authentication_graph() {
        assertThat(authHandler).isNotNull();
        assertThat(readyInstaller).isNotNull();
        assertThat(authenticationService).isNotNull();
        assertThat(credentialStore).isNotNull();
        assertThat(persistenceService).isNotNull();
        assertThat(refreshCoordinator).isNotNull();
        assertThat(requestExecutor).isNotNull();
        assertThat(authenticationGateway).isNotNull();
        assertThat(workbenchGateway).isNotNull();
        assertThat(dataScopeValidator).isNotNull();
    }

    @Test
    void production_persistence_uses_durable_secret_cleanup_lifecycle() {
        assertThat(ReflectionTestUtils.getField(persistenceService, "cleanupRepository"))
                .isNotNull();
        assertThat(ReflectionTestUtils.getField(persistenceService, "cleanupService"))
                .isNotNull();
        assertThat(ReflectionTestUtils.getField(persistenceService, "requiresNew"))
                .isNotNull();
        assertThat(java.util.Arrays.stream(OaSessionPersistenceService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)
                .toList())
                .doesNotContain(OaSessionCredentialStore.class);
    }
}
