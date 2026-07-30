package com.wzx.babiq.server.application.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Prepares one-shot desktop handshake files when Spring Boot is started directly from IDEA.
 */
public final class BusinessDirectDevelopmentSessionBootstrap {

    public static final String ENABLED_ENV = "HUITAI_BUSINESS_DIRECT_DEVELOPMENT";
    public static final String RUNTIME_DIR_ENV = "HUITAI_BUSINESS_RUNTIME_DIR";
    public static final String PORT_ENV = "HUITAI_BUSINESS_BACKEND_PORT";
    private static final String SESSION_FILE_NAME = "development-session.json";
    private static final String KEYSTORE_PASSWORD_FILE_NAME = "backend-keystore-password";
    private static final int TOKEN_BYTES = 32;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path runtimeDir;
    private final Path tokenFile;
    private final Path sessionFile;
    private final int port;

    public BusinessDirectDevelopmentSessionBootstrap(
            BusinessDesktopModeProperties properties,
            int port) {
        this(properties.runtimeDir(), properties.sessionTokenFile(), port);
    }

    public BusinessDirectDevelopmentSessionBootstrap(Path runtimeDir, Path tokenFile, int port) {
        this.runtimeDir = normalize(runtimeDir, "runtimeDir");
        this.tokenFile = normalize(tokenFile, "tokenFile");
        this.sessionFile = this.runtimeDir.resolve(SESSION_FILE_NAME);
        this.port = requirePort(port);
        requireInside(this.runtimeDir, this.tokenFile, "tokenFile");
    }

    public PreparedSession prepare() {
        try {
            Files.createDirectories(runtimeDir);
            rejectLink(runtimeDir);
            ensureBackendKeyStorePassword();
            deleteRegularIfPresent(tokenFile);
            deleteRegularIfPresent(sessionFile);

            byte[] tokenBytes = new byte[TOKEN_BYTES];
            RANDOM.nextBytes(tokenBytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            java.util.Arrays.fill(tokenBytes, (byte) 0);

            Map<String, String> descriptor = new LinkedHashMap<>();
            descriptor.put("url", "ws://127.0.0.1:" + port + "/ws/agent");
            descriptor.put("desktopInstanceId", UUID.randomUUID().toString());
            descriptor.put("desktopSessionId", UUID.randomUUID().toString());
            descriptor.put("desktopSessionToken", token);
            descriptor.put("localOrigin", "http://127.0.0.1:" + port);
            writeOwnerOnly(tokenFile, token.getBytes(StandardCharsets.US_ASCII));
            writeOwnerOnly(sessionFile, JSON.writeValueAsBytes(descriptor));
            return new PreparedSession(sessionFile, tokenFile);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "direct business desktop session could not be prepared", failure);
        }
    }

    public static PreparedSession prepareIfRequested(String[] args, Map<String, String> environment) {
        if (!"1".equals(environment.get(ENABLED_ENV))) {
            return null;
        }
        Path runtime = Path.of(option(
                args,
                "--babiq.business.runtime-dir",
                environment.getOrDefault(
                        RUNTIME_DIR_ENV,
                        Path.of(System.getProperty("user.dir"), ".tmp-business-desktop-direct",
                                ".huitai-agent-desktop", "agent").toString())));
        Path tokenFile = Path.of(option(
                args,
                "--babiq.business.session-token-file",
                runtime.resolve("session-token").toString()));
        int port = Integer.parseInt(option(
                args,
                "--server.port",
                environment.getOrDefault(PORT_ENV, "49391")));
        return new BusinessDirectDevelopmentSessionBootstrap(runtime, tokenFile, port).prepare();
    }

    private static String option(String[] args, String key, String fallback) {
        String prefix = key + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return fallback;
    }

    private static void writeOwnerOnly(Path path, byte[] bytes) throws IOException {
        rejectLink(path);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        applyOwnerOnly(path);
    }

    private void ensureBackendKeyStorePassword() throws IOException {
        if (System.getProperty("babiq.secrets.keystore-password") != null
                && !System.getProperty("babiq.secrets.keystore-password").isBlank()) {
            return;
        }
        Path passwordFile = runtimeDir.resolve(KEYSTORE_PASSWORD_FILE_NAME);
        rejectLink(passwordFile);
        String password;
        if (Files.exists(passwordFile, LinkOption.NOFOLLOW_LINKS)) {
            password = Files.readString(passwordFile, StandardCharsets.US_ASCII).strip();
        } else {
            byte[] bytes = new byte[TOKEN_BYTES];
            RANDOM.nextBytes(bytes);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            java.util.Arrays.fill(bytes, (byte) 0);
            writeOwnerOnly(passwordFile, password.getBytes(StandardCharsets.US_ASCII));
        }
        if (password.length() != 43) {
            throw new IllegalStateException("direct business desktop KeyStore password is invalid");
        }
        System.setProperty("babiq.secrets.keystore-password", password);
    }

    private static void deleteRegularIfPresent(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        rejectLink(path);
        Files.deleteIfExists(path);
    }

    private static void rejectLink(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(path)
                || Files.readAttributes(
                path,
                java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).isOther())) {
            throw new IllegalArgumentException(
                    "direct business desktop session path must not be a link");
        }
    }

    private static void applyOwnerOnly(Path path) {
        try {
            var view = Files.getFileAttributeView(
                    path,
                    java.nio.file.attribute.PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view != null) {
                view.setPermissions(java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows ACLs are inherited from the controlled runtime directory.
        }
    }

    private static Path normalize(Path path, String name) {
        if (path == null) {
            throw new IllegalArgumentException(name + " must be configured");
        }
        return path.toAbsolutePath().normalize();
    }

    private static void requireInside(Path root, Path candidate, String name) {
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException(name + " must resolve below runtimeDir");
        }
    }

    private static int requirePort(int value) {
        if (value < 1 || value > 65_535) {
            throw new IllegalArgumentException("direct business desktop port must be in 1..65535");
        }
        return value;
    }

    public static final class PreparedSession implements AutoCloseable {
        private final Path sessionFile;
        private final Path tokenFile;

        private PreparedSession(Path sessionFile, Path tokenFile) {
            this.sessionFile = sessionFile;
            this.tokenFile = tokenFile;
        }

        @Override
        public void close() {
            try {
                deleteRegularIfPresent(sessionFile);
                deleteRegularIfPresent(tokenFile);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "direct business desktop session could not be cleaned up", failure);
            }
        }
    }
}
