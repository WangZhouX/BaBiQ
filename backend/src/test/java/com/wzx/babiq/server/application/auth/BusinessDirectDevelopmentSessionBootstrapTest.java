package com.wzx.babiq.server.application.auth;

import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDirectDevelopmentSessionBootstrapTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearDirectDevelopmentPassword() {
        System.clearProperty("babiq.secrets.keystore-password");
    }

    @Test
    void publishesFreshAuthenticatedSessionFilesForDirectSpringBootDevelopment() throws Exception {
        BusinessDesktopModeProperties properties = properties();
        BusinessDirectDevelopmentSessionBootstrap bootstrap =
                new BusinessDirectDevelopmentSessionBootstrap(properties, 49391);

        bootstrap.prepare();

        Path tokenFile = properties.sessionTokenFile();
        Path sessionFile = properties.runtimeDir().resolve("development-session.json");
        String token = Files.readString(tokenFile, StandardCharsets.US_ASCII);
        String descriptor = Files.readString(sessionFile, StandardCharsets.UTF_8);

        assertThat(token).hasSize(43);
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
        assertThat(descriptor)
                .contains("\"url\":\"ws://127.0.0.1:49391/ws/agent\"")
                .contains("\"localOrigin\":\"http://127.0.0.1:49391\"")
                .contains("\"desktopInstanceId\"")
                .contains("\"desktopSessionId\"")
                .contains("\"desktopSessionToken\":\"" + token + "\"");
    }

    @Test
    void replacesStaleDirectDevelopmentSessionFiles() throws Exception {
        BusinessDesktopModeProperties properties = properties();
        Path sessionFile = properties.runtimeDir().resolve("development-session.json");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(properties.sessionTokenFile(), "stale", StandardCharsets.US_ASCII);
        Files.writeString(sessionFile, "stale", StandardCharsets.UTF_8);

        new BusinessDirectDevelopmentSessionBootstrap(properties, 49391).prepare();

        assertThat(Files.readString(properties.sessionTokenFile(), StandardCharsets.US_ASCII))
                .isNotEqualTo("stale");
        assertThat(Files.readString(sessionFile, StandardCharsets.UTF_8))
                .isNotEqualTo("stale");
    }

    @Test
    void profile_direct_development_enables_session_without_environment_switch() throws Exception {
        BusinessDesktopModeProperties properties = properties();

        BusinessDirectDevelopmentSessionBootstrap.PreparedSession prepared =
                BusinessDirectDevelopmentSessionBootstrap.prepareIfRequested(
                        new String[]{"--spring.profiles.active=business-desktop,direct-development"},
                        java.util.Map.of(
                                BusinessDirectDevelopmentSessionBootstrap.RUNTIME_DIR_ENV,
                                properties.runtimeDir().toString(),
                                BusinessDirectDevelopmentSessionBootstrap.PORT_ENV,
                                "49391"));

        assertThat(prepared).isNotNull();
        assertThat(Files.exists(properties.runtimeDir().resolve("development-session.json"))).isTrue();
        prepared.close();
    }

    private BusinessDesktopModeProperties properties() {
        Path runtime = tempDir.toAbsolutePath().normalize();
        return new BusinessDesktopModeProperties(
                true,
                runtime,
                runtime.resolve("data/business.db"),
                runtime.resolve("secrets/business.jceks"),
                runtime.resolve("logs/backend.log"),
                runtime.resolve("memory"),
                runtime.resolve("teams"),
                runtime.resolve("instance.lock"),
                runtime.resolve("session-token"),
                true,
                "127.0.0.1",
                "http://127.0.0.1:49391",
                256 * 1024,
                128 * 1024,
                128 * 1024,
                64 * 1024,
                64 * 1024,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                Duration.ofMinutes(2),
                Duration.ofSeconds(10));
    }
}
