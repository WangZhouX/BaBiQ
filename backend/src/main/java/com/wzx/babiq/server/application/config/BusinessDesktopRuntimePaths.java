package com.wzx.babiq.server.application.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 创建业务后端启动所需的隔离目录。
 *
 * <p>会话 Token 是桌面父进程创建并由后端单次消费的凭据，本组件只创建其父目录，
 * 绝不创建或截断 Token 文件本身。</p>
 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessDesktopRuntimePaths implements AutoCloseable {

    private static final String APPENDER_NAME = "BUSINESS_DESKTOP_FILE";

    private final BusinessDesktopModeProperties properties;
    private final Logger rootLogger;
    private final RollingFileAppender<ILoggingEvent> fileAppender;
    private final SizeAndTimeBasedRollingPolicy<ILoggingEvent> rollingPolicy;
    private final PatternLayoutEncoder encoder;
    private boolean closed;

    public BusinessDesktopRuntimePaths(BusinessDesktopModeProperties properties) {
        this.properties = properties;
        createRequiredDirectories();
        LoggingRegistration logging = configureBusinessFileLogging(properties.logPath());
        this.rootLogger = logging.rootLogger();
        this.fileAppender = logging.fileAppender();
        this.rollingPolicy = logging.rollingPolicy();
        this.encoder = logging.encoder();
    }

    public Path runtimeDir() {
        return properties.runtimeDir();
    }

    public Path attachmentClipboardRoot() {
        return properties.attachmentClipboardRoot();
    }

    private void createRequiredDirectories() {
        Set<Path> directories = new LinkedHashSet<>();
        directories.add(properties.runtimeDir());
        addParent(directories, properties.databasePath());
        addParent(directories, properties.keyStorePath());
        addParent(directories, properties.logPath());
        directories.add(properties.memoryRoot());
        directories.add(properties.teamRoot());
        directories.add(properties.attachmentClipboardRoot());
        addParent(directories, properties.backendLockPath());
        addParent(directories, properties.sessionTokenFile());

        try {
            rejectLinkedRuntimePaths();
            for (Path directory : directories) {
                Files.createDirectories(directory);
            }
            verifyRealPathContainment(directories);
            for (Path directory : directories) {
                applyBestEffortOwnerOnlyPermissions(directory, true);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create business desktop runtime directories", exception);
        }
    }

    private static void addParent(Set<Path> directories, Path file) {
        Path parent = file.getParent();
        if (parent != null) {
            directories.add(parent);
        }
    }

    private void rejectLinkedRuntimePaths() throws IOException {
        for (Path path : controlledPaths()) {
            rejectLinksInExistingPath(path);
        }
    }

    private static void rejectLinksInExistingPath(Path path) throws IOException {
        Path current = path.getRoot();
        rejectLinkIfPresent(current);
        for (Path segment : path) {
            current = current == null ? segment : current.resolve(segment);
            rejectLinkIfPresent(current);
        }
    }

    private static void rejectLinkIfPresent(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(path) || attributes.isOther()) {
            throw new IllegalArgumentException("business desktop runtime path must not contain a link");
        }
    }

    private void verifyRealPathContainment(Set<Path> directories) throws IOException {
        Path realRuntime = properties.runtimeDir().toRealPath();
        for (Path directory : directories) {
            if (!directory.toRealPath().startsWith(realRuntime)) {
                throw new IllegalArgumentException("business desktop runtime path escaped runtimeDir");
            }
        }
    }

    private Set<Path> controlledPaths() {
        return Set.of(
                properties.runtimeDir(),
                properties.databasePath(),
                properties.keyStorePath(),
                properties.logPath(),
                properties.memoryRoot(),
                properties.teamRoot(),
                properties.attachmentClipboardRoot(),
                properties.backendLockPath(),
                properties.sessionTokenFile());
    }

    private static synchronized LoggingRegistration configureBusinessFileLogging(Path logPath) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            throw new IllegalStateException("Logback is required for business desktop file logging");
        }
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) instanceof RollingFileAppender<?> existing) {
            root.detachAppender(APPENDER_NAME);
            existing.stop();
            if (existing.getRollingPolicy() != null) {
                existing.getRollingPolicy().stop();
            }
            if (existing.getEncoder() != null) {
                existing.getEncoder().stop();
            }
        }

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{40} - %msg%n");
        encoder.start();

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext(context);
        appender.setName(APPENDER_NAME);
        appender.setFile(logPath.toString());
        appender.setAppend(true);
        appender.setEncoder(encoder);

        SizeAndTimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new SizeAndTimeBasedRollingPolicy<>();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(appender);
        rollingPolicy.setFileNamePattern(logPath + ".%d{yyyy-MM-dd}.%i.gz");
        rollingPolicy.setMaxFileSize(FileSize.valueOf("10MB"));
        rollingPolicy.setMaxHistory(7);
        rollingPolicy.setTotalSizeCap(FileSize.valueOf("100MB"));
        rollingPolicy.start();
        appender.setRollingPolicy(rollingPolicy);
        appender.start();
        if (!appender.isStarted()) {
            rollingPolicy.stop();
            encoder.stop();
            throw new IllegalStateException("failed to start business desktop file logging");
        }
        root.addAppender(appender);
        return new LoggingRegistration(root, appender, rollingPolicy, encoder);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        synchronized (BusinessDesktopRuntimePaths.class) {
            if (rootLogger.getAppender(APPENDER_NAME) == fileAppender) {
                rootLogger.detachAppender(fileAppender);
            }
            fileAppender.stop();
            rollingPolicy.stop();
            encoder.stop();
        }
    }

    static void applyBestEffortOwnerOnlyPermissions(Path path, boolean directory) {
        try {
            PosixFileAttributeView posixView = Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posixView != null) {
                Set<PosixFilePermission> permissions = directory
                        ? Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE)
                        : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(path, permissions);
                return;
            }

            AclFileAttributeView aclView = Files.getFileAttributeView(
                    path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (aclView == null) {
                return;
            }
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            AclEntry.Builder entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) {
                entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            }
            aclView.setAcl(Set.of(entry.build()).stream().toList());
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Permission APIs vary by file system; path/link checks remain mandatory.
        }
    }

    private record LoggingRegistration(
            Logger rootLogger,
            RollingFileAppender<ILoggingEvent> fileAppender,
            SizeAndTimeBasedRollingPolicy<ILoggingEvent> rollingPolicy,
            PatternLayoutEncoder encoder
    ) {
    }
}
