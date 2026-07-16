package com.wzx.babiq.server.application.auth;

import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.application.config.BusinessBackendInstanceLock;
import com.wzx.babiq.server.application.config.BusinessDesktopRuntimePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class DesktopSessionTokenProviderTest {

    private static final String TOKEN = "A".repeat(43);

    @TempDir
    Path tempDir;

    @Test
    void consumesTheTokenOnceDeletesTheFileAndKeepsOnlyARedactedDigest(CapturedOutput output) throws Exception {
        Path tokenFile = writeRestrictedToken(tempDir.resolve("session-token"), TOKEN);

        DesktopSessionTokenProvider provider = new DesktopSessionTokenProvider(tokenFile);

        assertThat(Files.notExists(tokenFile)).isTrue();
        assertThat(provider.matches(TOKEN)).isTrue();
        assertThat(provider.matches("B".repeat(43))).isFalse();
        assertThat(provider.matches(null)).isFalse();
        assertThat(provider.toString())
                .contains("[REDACTED]")
                .doesNotContain(TOKEN)
                .doesNotContain(tokenFile.toString());
        assertThat(output).doesNotContain(TOKEN);
    }

    @Test
    void rejectsAndDeletesEmptyOrOversizedTokenFilesWithoutExposingTheirContents() throws Exception {
        Path empty = writeRestrictedToken(tempDir.resolve("empty-token"), "");
        assertThatThrownBy(() -> new DesktopSessionTokenProvider(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session token")
                .hasMessageNotContaining(empty.toString());
        assertThat(Files.notExists(empty)).isTrue();

        String oversizedSecret = "secret-" + "X".repeat(4096);
        Path oversized = writeRestrictedToken(tempDir.resolve("oversized-token"), oversizedSecret);
        assertThatThrownBy(() -> new DesktopSessionTokenProvider(oversized))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session token")
                .hasMessageNotContaining(oversizedSecret)
                .hasMessageNotContaining(oversized.toString());
        assertThat(Files.notExists(oversized)).isTrue();
    }

    @Test
    void acceptsOnlyA256BitUnpaddedBase64UrlTokenInTheFileAndCandidates() throws Exception {
        for (String invalid : List.of(
                "A".repeat(42),
                "A".repeat(44),
                "A".repeat(42) + "=",
                "A".repeat(42) + "!")) {
            Path tokenFile = writeRestrictedToken(
                    tempDir.resolve("invalid-token-" + System.nanoTime()), invalid);
            assertThatThrownBy(() -> new DesktopSessionTokenProvider(tokenFile))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("session token")
                    .hasMessageNotContaining(invalid);
            assertThat(Files.notExists(tokenFile)).isTrue();
        }

        Path nonAsciiFile = tempDir.resolve("non-ascii-token");
        Files.writeString(nonAsciiFile, "A".repeat(42) + "é", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new DesktopSessionTokenProvider(nonAsciiFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session token")
                .hasMessageNotContaining("é");
        assertThat(Files.notExists(nonAsciiFile)).isTrue();

        Path validFile = writeRestrictedToken(tempDir.resolve("valid-token"), TOKEN);
        DesktopSessionTokenProvider provider = new DesktopSessionTokenProvider(validFile);
        assertThat(provider.matches(TOKEN)).isTrue();
        assertThat(provider.matches("A".repeat(42))).isFalse();
        assertThat(provider.matches("A".repeat(44))).isFalse();
        assertThat(provider.matches("A".repeat(42) + "=")).isFalse();
        assertThat(provider.matches("A".repeat(42) + "!")).isFalse();
        assertThat(provider.matches("A".repeat(42) + "é")).isFalse();
    }

    @Test
    void consumesAndDeletesOnlyTheClaimedFileWhenThePublishedPathIsReplaced() throws Exception {
        Path tokenFile = writeRestrictedToken(tempDir.resolve("replaceable-token"), TOKEN);
        TokenFileClaim claim = TokenFileClaim.acquire(tokenFile);
        String replacementToken = "B".repeat(43);
        Path oldAlias = tempDir.resolve("claimed-token-alias");
        if (Files.exists(tokenFile)) {
            Files.move(tokenFile, oldAlias);
        }
        writeRestrictedToken(tokenFile, replacementToken);

        DesktopSessionTokenProvider provider = new DesktopSessionTokenProvider(claim);

        assertThat(provider.matches(TOKEN)).isTrue();
        assertThat(provider.matches(replacementToken)).isFalse();
        assertThat(Files.readString(tokenFile, StandardCharsets.US_ASCII)).isEqualTo(replacementToken);
        assertThat(Files.notExists(oldAlias)).isTrue();
    }

    @Test
    void closeRetriesTransientChannelCloseFailures() {
        AtomicInteger closeAttempts = new AtomicInteger();
        ClaimChannel channel = new ClaimChannel() {
            @Override
            public byte[] readBounded(int maxBytes) {
                return TOKEN.getBytes(StandardCharsets.US_ASCII);
            }

            @Override
            public void close() throws Exception {
                if (closeAttempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("channel close failed");
                }
            }
        };
        TokenFileClaim claim = new TokenFileClaim(channel);

        claim.close();
        assertThat(closeAttempts).hasValue(2);
    }

    @Test
    void closeStopsAfterThreeFailuresAndSuppressesEveryAttempt() {
        AtomicInteger closeAttempts = new AtomicInteger();
        ClaimChannel channel = new ClaimChannel() {
            @Override
            public byte[] readBounded(int maxBytes) {
                return TOKEN.getBytes(StandardCharsets.US_ASCII);
            }

            @Override
            public void close() throws Exception {
                closeAttempts.incrementAndGet();
                throw new IllegalStateException("close failure");
            }
        };
        TokenFileClaim claim = new TokenFileClaim(channel);

        assertThatThrownBy(claim::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be deleted")
                .satisfies(exception -> assertThat(exception.getSuppressed()).hasSize(3));
        assertThat(closeAttempts).hasValue(3);
    }

    @Test
    void tokenReadFailureRemainsPrimaryWhenCleanupAlsoFails() {
        IllegalStateException readFailure = new IllegalStateException("primary read failure");
        ClaimChannel channel = new ClaimChannel() {
            @Override
            public byte[] readBounded(int maxBytes) {
                throw readFailure;
            }

            @Override
            public void close() throws Exception {
                throw new IllegalStateException("cleanup close failure");
            }
        };
        TokenFileClaim claim = new TokenFileClaim(channel);

        assertThatThrownBy(() -> new DesktopSessionTokenProvider(claim))
                .isSameAs(readFailure)
                .satisfies(exception -> assertThat(exception.getSuppressed()).hasSize(1));
    }

    @Test
    void unsupportedDeleteOnCloseOpenFailsClosedWithoutActivelyDeletingTheSource() throws Exception {
        Path tokenFile = writeRestrictedToken(tempDir.resolve("unsupported-token"), TOKEN);

        assertThatThrownBy(() -> TokenFileClaim.acquire(tokenFile, path -> {
            throw new UnsupportedOperationException("DELETE_ON_CLOSE unsupported");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session token")
                .hasMessageNotContaining(tokenFile.toString());
        assertThat(Files.readString(tokenFile, StandardCharsets.US_ASCII)).isEqualTo(TOKEN);
    }

    @Test
    void rejectsAReparsePointInTheTokenPathAndDeletesOnlyTheLink() throws Exception {
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path realToken = writeRestrictedToken(outside.resolve("real-token"), TOKEN);
        Path junction = tempDir.resolve("token-junction");
        createDirectoryJunction(junction, outside);
        Path linkedToken = junction.resolve("real-token");

        assertThatThrownBy(() -> new DesktopSessionTokenProvider(linkedToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link")
                .hasMessageNotContaining(linkedToken.toString());
        assertThat(Files.exists(realToken)).isTrue();
    }

    @Test
    void businessStartupFailsFastWhenTheOneShotTokenFileIsMissing() {
        Path missingToken = tempDir.resolve("missing-token");
        new ApplicationContextRunner()
                .withPropertyValues("babiq.business.enabled=true")
                .withBean(BusinessDesktopModeProperties.class, () -> properties(missingToken))
                .withUserConfiguration(BusinessStartupConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(findCause(context.getStartupFailure(), IllegalStateException.class))
                            .isNotNull()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("session token")
                            .hasMessageNotContaining(missingToken.toString());
                });
    }

    @Test
    void businessStartupAcquiresTheInstanceLockBeforeConsumingTheOneShotToken() throws Exception {
        Path tokenFile = writeRestrictedToken(tempDir.resolve("session-token"), TOKEN);
        BusinessDesktopModeProperties properties = properties(tokenFile);

        try (BusinessBackendInstanceLock ignored = BusinessBackendInstanceLock.acquire(properties.backendLockPath())) {
            new ApplicationContextRunner()
                    .withPropertyValues("babiq.business.enabled=true")
                    .withBean(BusinessDesktopModeProperties.class, () -> properties)
                    .withUserConfiguration(BusinessStartupConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(findCause(context.getStartupFailure(), IllegalStateException.class))
                                .isNotNull()
                                .hasMessageContaining("already running");
                        assertThat(Files.exists(tokenFile)).isTrue();
                    });
        }
    }

    private Path writeRestrictedToken(Path path, String token) throws Exception {
        Files.writeString(path, token, StandardCharsets.US_ASCII);
        PosixFileAttributeView posixView = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posixView != null) {
            Files.setPosixFilePermissions(
                    path,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return path;
        }
        AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (aclView != null) {
            AclEntry ownerEntry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            aclView.setAcl(List.of(ownerEntry));
        }
        return path;
    }

    private BusinessDesktopModeProperties properties(Path tokenFile) {
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
                tokenFile,
                true,
                "127.0.0.1",
                "http://127.0.0.1",
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

    private static void createDirectoryJunction(Path junction, Path target) throws Exception {
        Process process = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", "-")
                .redirectErrorStream(true)
                .start();
        String command = "New-Item -ItemType Junction -Path '"
                + escapePowerShellLiteral(junction.toString())
                + "' -Target '"
                + escapePowerShellLiteral(target.toString())
                + "' | Out-Null\n";
        process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }

    private static String escapePowerShellLiteral(String value) {
        return value.replace("'", "''");
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null && current.getCause() != current) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            DesktopSessionTokenProvider.class,
            BusinessDesktopRuntimePaths.class,
            BusinessBackendInstanceLock.class
    })
    static class BusinessStartupConfiguration {
    }
}
