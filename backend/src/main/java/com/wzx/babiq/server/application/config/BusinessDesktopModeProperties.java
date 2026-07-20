package com.wzx.babiq.server.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

/**
 * 业务桌面内置 Agent 的隔离运行配置。
 *
 * <p>该配置镜像业务 profile 实际交给数据库、KeyStore、日志、记忆和团队组件的路径，
 * 并在应用创建这些组件之前统一验证运行目录边界与本机握手约束。</p>
 */
@ConfigurationProperties(prefix = "babiq.business")
public record BusinessDesktopModeProperties(
        boolean enabled,
        Path runtimeDir,
        Path databasePath,
        Path keyStorePath,
        Path logPath,
        Path memoryRoot,
        Path teamRoot,
        Path backendLockPath,
        Path sessionTokenFile,
        Path attachmentClipboardRoot,
        boolean authenticationRequired,
        String serverAddress,
        String allowedOrigins,
        int maxEnvelopeBytes,
        int maxCatalogPayloadBytes,
        int maxContextPayloadBytes,
        int maxActionInputBytes,
        int maxActionResultBytes,
        Duration acceptTimeout,
        Duration previewTimeout,
        Duration approvalTimeout,
        Duration executeTimeout,
        Duration reconciliationGraceTimeout
) {

    @ConstructorBinding
    public BusinessDesktopModeProperties {
        if (enabled) {
            runtimeDir = normalize(runtimeDir, "runtimeDir");
            databasePath = normalize(databasePath, "databasePath");
            keyStorePath = normalize(keyStorePath, "keyStorePath");
            logPath = normalize(logPath, "logPath");
            memoryRoot = normalize(memoryRoot, "memoryRoot");
            teamRoot = normalize(teamRoot, "teamRoot");
            backendLockPath = normalize(backendLockPath, "backendLockPath");
            sessionTokenFile = normalize(sessionTokenFile, "sessionTokenFile");
            attachmentClipboardRoot = normalize(attachmentClipboardRoot, "attachmentClipboardRoot");

            requireInside(runtimeDir, databasePath, "databasePath");
            requireInside(runtimeDir, keyStorePath, "keyStorePath");
            requireInside(runtimeDir, logPath, "logPath");
            requireInside(runtimeDir, memoryRoot, "memoryRoot");
            requireInside(runtimeDir, teamRoot, "teamRoot");
            requireInside(runtimeDir, backendLockPath, "backendLockPath");
            requireInside(runtimeDir, sessionTokenFile, "sessionTokenFile");
            requireInside(runtimeDir, attachmentClipboardRoot, "attachmentClipboardRoot");
            if (attachmentClipboardRoot.equals(runtimeDir)) {
                throw new IllegalArgumentException(
                        "attachmentClipboardRoot must resolve strictly below runtimeDir");
            }
            if (!backendLockPath.equals(runtimeDir.resolve("instance.lock"))) {
                throw new IllegalArgumentException("backendLockPath must be runtimeDir/instance.lock");
            }

            if (!authenticationRequired) {
                throw new IllegalArgumentException("business desktop authentication must be required");
            }
            if (!isLoopbackAddress(serverAddress)) {
                throw new IllegalArgumentException("business desktop server address must be loopback");
            }
            if (containsWildcardOrigin(allowedOrigins)) {
                throw new IllegalArgumentException("business desktop allowed origins must not contain a wildcard");
            }
            validateLoopbackOrigins(allowedOrigins);

            requirePositive(maxEnvelopeBytes, "maxEnvelopeBytes");
            requirePositive(maxCatalogPayloadBytes, "maxCatalogPayloadBytes");
            requirePositive(maxContextPayloadBytes, "maxContextPayloadBytes");
            requirePositive(maxActionInputBytes, "maxActionInputBytes");
            requirePositive(maxActionResultBytes, "maxActionResultBytes");
            requirePositive(acceptTimeout, "acceptTimeout");
            requirePositive(previewTimeout, "previewTimeout");
            requirePositive(approvalTimeout, "approvalTimeout");
            requirePositive(executeTimeout, "executeTimeout");
            requirePositive(reconciliationGraceTimeout, "reconciliationGraceTimeout");
        }
    }

    /**
     * Backward-compatible constructor for direct test and embedding call sites created before
     * the controlled clipboard attachment root was introduced.
     */
    public BusinessDesktopModeProperties(
            boolean enabled,
            Path runtimeDir,
            Path databasePath,
            Path keyStorePath,
            Path logPath,
            Path memoryRoot,
            Path teamRoot,
            Path backendLockPath,
            Path sessionTokenFile,
            boolean authenticationRequired,
            String serverAddress,
            String allowedOrigins,
            int maxEnvelopeBytes,
            int maxCatalogPayloadBytes,
            int maxContextPayloadBytes,
            int maxActionInputBytes,
            int maxActionResultBytes,
            Duration acceptTimeout,
            Duration previewTimeout,
            Duration approvalTimeout,
            Duration executeTimeout,
            Duration reconciliationGraceTimeout
    ) {
        this(
                enabled,
                runtimeDir,
                databasePath,
                keyStorePath,
                logPath,
                memoryRoot,
                teamRoot,
                backendLockPath,
                sessionTokenFile,
                runtimeDir == null ? null : runtimeDir.resolve("attachments").resolve("clipboard"),
                authenticationRequired,
                serverAddress,
                allowedOrigins,
                maxEnvelopeBytes,
                maxCatalogPayloadBytes,
                maxContextPayloadBytes,
                maxActionInputBytes,
                maxActionResultBytes,
                acceptTimeout,
                previewTimeout,
                approvalTimeout,
                executeTimeout,
                reconciliationGraceTimeout);
    }

    private static Path normalize(Path path, String propertyName) {
        if (path == null) {
            throw new IllegalArgumentException(propertyName + " must be configured");
        }
        return path.toAbsolutePath().normalize();
    }

    private static void requireInside(Path runtimeDir, Path candidate, String propertyName) {
        if (!candidate.startsWith(runtimeDir)) {
            throw new IllegalArgumentException(propertyName + " must resolve below runtimeDir");
        }
    }

    private static boolean isLoopbackAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(value.strip()).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static boolean containsWildcardOrigin(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .anyMatch(origin -> origin.isEmpty() || origin.contains("*"));
    }

    private static void validateLoopbackOrigins(String value) {
        for (String configuredOrigin : value.split(",")) {
            URI origin;
            try {
                origin = new URI(configuredOrigin.strip());
            } catch (URISyntaxException exception) {
                throw invalidHttpOrigin();
            }
            String scheme = origin.getScheme();
            String path = origin.getRawPath();
            boolean validShape = ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && origin.getHost() != null
                    && origin.getRawUserInfo() == null
                    && (path == null || path.isEmpty())
                    && origin.getRawQuery() == null
                    && origin.getRawFragment() == null
                    && (origin.getPort() == -1 || origin.getPort() > 0 && origin.getPort() <= 65_535);
            if (!validShape) {
                throw invalidHttpOrigin();
            }
            if (!isLoopbackAddress(origin.getHost())) {
                throw new IllegalArgumentException("business desktop HTTP origin must use a loopback host");
            }
        }
    }

    private static IllegalArgumentException invalidHttpOrigin() {
        return new IllegalArgumentException("business desktop allowed origins must be valid HTTP origins");
    }

    private static void requirePositive(int value, String propertyName) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
    }

    private static void requirePositive(Duration value, String propertyName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
    }
}
