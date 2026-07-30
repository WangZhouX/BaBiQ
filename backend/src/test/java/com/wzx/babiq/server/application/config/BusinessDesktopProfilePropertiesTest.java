package com.wzx.babiq.server.application.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDesktopProfilePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertyBindingConfiguration.class)
            .withPropertyValues("spring.profiles.active=business-desktop");

    @TempDir
    Path runtimeDir;

    @Test
    void profileUsesLoopbackAndKeepsEveryBackendArtifactInsideTheBusinessRuntime() {
        runWithRuntime().run(context -> {
            assertThat(context).hasNotFailed();

            Environment environment = context.getEnvironment();
            BusinessDesktopModeProperties properties = context.getBean(BusinessDesktopModeProperties.class);
            Path runtime = runtimeDir.toAbsolutePath().normalize();

            assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(environment.getProperty("server.port", Integer.class)).isZero();
            assertThat(environment.getProperty("babiq.ws.allowed-origins"))
                    .isNotBlank()
                    .doesNotContain("*");

            List<Path> configuredPaths = List.of(
                    path(environment, "babiq.persistence.database-path"),
                    path(environment, "babiq.secrets.keystore-path"),
                    path(environment, "logging.file.name"),
                    path(environment, "babiq.memory.long-term.root-dir"),
                    path(environment, "babiq.team.root-dir"),
                    properties.backendLockPath(),
                    properties.sessionTokenFile(),
                    properties.attachmentClipboardRoot()
            );
            assertThat(configuredPaths)
                    .allSatisfy(path -> assertThat(path.toAbsolutePath().normalize().startsWith(runtime)).isTrue());

            assertThat(properties.enabled()).isTrue();
            assertThat(properties.runtimeDir()).isEqualTo(runtime);
            assertThat(properties.databasePath()).isEqualTo(configuredPaths.get(0));
            assertThat(properties.keyStorePath()).isEqualTo(configuredPaths.get(1));
            assertThat(properties.logPath()).isEqualTo(configuredPaths.get(2));
            assertThat(properties.memoryRoot()).isEqualTo(configuredPaths.get(3));
            assertThat(properties.teamRoot()).isEqualTo(configuredPaths.get(4));
            assertThat(properties.attachmentClipboardRoot())
                    .isEqualTo(runtime.resolve("attachments").resolve("clipboard"));
            assertThat(properties.authenticationRequired()).isTrue();
        });
    }

    @Test
    void directDevelopmentProfileProvidesTheIdeaLoopbackPort() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertyBindingConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=business-desktop,direct-development",
                        "babiq.business.runtime-dir=" + runtimeDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("server.address"))
                            .isEqualTo("127.0.0.1");
                    assertThat(context.getEnvironment().getProperty("server.port", Integer.class))
                            .isEqualTo(49_391);
                    assertThat(context.getEnvironment().getProperty("babiq.ws.allowed-origins"))
                            .isEqualTo("http://127.0.0.1:49391");
                });
    }

    @Test
    void runtimePathsCreateAndExposeTheControlledClipboardDirectory() {
        runWithRuntime().run(context -> {
            assertThat(context).hasNotFailed();
            BusinessDesktopModeProperties properties = context.getBean(BusinessDesktopModeProperties.class);

            try (BusinessDesktopRuntimePaths paths = new BusinessDesktopRuntimePaths(properties)) {
                assertThat(paths.attachmentClipboardRoot()).isEqualTo(properties.attachmentClipboardRoot());
                assertThat(Files.isDirectory(paths.attachmentClipboardRoot())).isTrue();
                assertThat(paths.attachmentClipboardRoot().startsWith(paths.runtimeDir())).isTrue();
                assertThat(paths.attachmentClipboardRoot()).isNotEqualTo(paths.runtimeDir());
            }
        });
    }

    @Test
    void profileDisablesLongTermMemoryMcpAndSkillsWithoutInventingUnrelatedFeatureFlags() {
        runWithRuntime().run(context -> {
            assertThat(context).hasNotFailed();
            Environment environment = context.getEnvironment();

            assertThat(environment.getProperty("babiq.memory.long-term.enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("babiq.memory.long-term.generate-enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("babiq.memory.long-term.read-enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("babiq.memory.long-term.retrieval-enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("babiq.mcp.enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("babiq.skills.enabled", Boolean.class)).isFalse();

            assertThat(environment.containsProperty("babiq.business.flow.enabled")).isFalse();
            assertThat(environment.containsProperty("babiq.business.team.enabled")).isFalse();
            assertThat(environment.containsProperty("babiq.business.work-unit.enabled")).isFalse();
            assertThat(environment.containsProperty("babiq.business.sub-agent.enabled")).isFalse();
        });
    }

    @Test
    void profileBindsTheProtocolLimitsAndPositiveActionTimeouts() {
        runWithRuntime().run(context -> {
            assertThat(context).hasNotFailed();
            BusinessDesktopModeProperties properties = context.getBean(BusinessDesktopModeProperties.class);

            assertThat(properties.maxEnvelopeBytes()).isEqualTo(256 * 1024);
            assertThat(properties.maxCatalogPayloadBytes()).isEqualTo(128 * 1024);
            assertThat(properties.maxContextPayloadBytes()).isEqualTo(128 * 1024);
            assertThat(properties.maxActionInputBytes()).isEqualTo(64 * 1024);
            assertThat(properties.maxActionResultBytes()).isEqualTo(64 * 1024);
            assertThat(List.of(
                    properties.acceptTimeout(),
                    properties.previewTimeout(),
                    properties.approvalTimeout(),
                    properties.executeTimeout(),
                    properties.reconciliationGraceTimeout()
            )).allMatch(timeout -> timeout.compareTo(Duration.ZERO) > 0);
        });
    }

    @Test
    void enabledModeRejectsAuthenticationBeingDisabled() {
        assertRejected("babiq.business.authentication-required=false", "authentication");
    }

    @Test
    void enabledModeRejectsNonLoopbackBinding() {
        assertRejected("server.address=0.0.0.0", "loopback");
    }

    @Test
    void enabledModeRejectsWildcardOrigins() {
        assertRejected("babiq.ws.allowed-origins=*", "wildcard");
    }

    @Test
    void enabledModeRejectsNonLoopbackAndMalformedOrigins() {
        assertRejected("babiq.ws.allowed-origins=https://attacker.example", "loopback");
        assertRejected("babiq.ws.allowed-origins=not-an-origin", "HTTP origin");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "babiq.persistence.database-path",
            "babiq.secrets.keystore-path",
            "logging.file.name",
            "babiq.memory.long-term.root-dir",
            "babiq.team.root-dir",
            "babiq.business.backend-lock-path",
            "babiq.business.session-token-file",
            "babiq.business.attachment-clipboard-root"
    })
    void enabledModeRejectsEveryResolvedPathOutsideItsRuntime(String propertyName) {
        Path escapedPath = runtimeDir.resolveSibling("escaped").resolve(propertyName.replace('.', '-') + ".data");
        assertRejected(propertyName + "=" + escapedPath, "runtimeDir");
    }

    @Test
    void enabledModeRequiresTheCanonicalLockFileForItsRuntime() {
        assertRejected(
                "babiq.business.backend-lock-path=" + runtimeDir.resolve("alternate.lock"),
                "instance.lock");
    }

    private ApplicationContextRunner runWithRuntime() {
        return contextRunner.withPropertyValues("babiq.business.runtime-dir=" + runtimeDir);
    }

    private void assertRejected(String override, String messageFragment) {
        runWithRuntime().withPropertyValues(override).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(messageFragment);
        });
    }

    private static Path path(Environment environment, String key) {
        return Path.of(environment.getRequiredProperty(key)).toAbsolutePath().normalize();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BusinessDesktopModeProperties.class)
    static class PropertyBindingConfiguration {
    }
}
