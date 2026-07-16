package com.wzx.babiq.server.application.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** 业务 Agent 后端的跨进程单实例文件锁。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessBackendInstanceLock implements AutoCloseable {

    private final Path lockPath;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    @Autowired
    public BusinessBackendInstanceLock(BusinessDesktopModeProperties properties,
                                       BusinessDesktopRuntimePaths runtimePaths) {
        this(acquire(properties.backendLockPath()));
    }

    private BusinessBackendInstanceLock(BusinessBackendInstanceLock acquired) {
        this(acquired.lockPath, acquired.channel, acquired.lock);
    }

    private BusinessBackendInstanceLock(Path lockPath, FileChannel channel, FileLock lock) {
        this.lockPath = lockPath;
        this.channel = channel;
        this.lock = lock;
    }

    public static BusinessBackendInstanceLock acquire(Path lockPath) {
        Path normalized = lockPath.toAbsolutePath().normalize();
        FileChannel channel = null;
        try {
            Path parent = normalized.getParent();
            if (parent != null) {
                rejectLinksInExistingPath(parent);
                Files.createDirectories(parent);
                rejectLinksInExistingPath(parent);
                BusinessDesktopRuntimePaths.applyBestEffortOwnerOnlyPermissions(parent, true);
            }
            rejectLinksInExistingPath(normalized);
            channel = FileChannel.open(normalized,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            rejectLinksInExistingPath(normalized);
            BusinessDesktopRuntimePaths.applyBestEffortOwnerOnlyPermissions(normalized, false);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                closeQuietly(channel);
                throw alreadyRunning();
            }
            return new BusinessBackendInstanceLock(normalized, channel, lock);
        } catch (OverlappingFileLockException exception) {
            closeQuietly(channel);
            throw alreadyRunning();
        } catch (IllegalArgumentException exception) {
            closeQuietly(channel);
            throw exception;
        } catch (IOException exception) {
            closeQuietly(channel);
            throw new IllegalStateException("failed to acquire business backend instance lock", exception);
        }
    }

    public Path lockPath() {
        return lockPath;
    }

    public boolean isHeld() {
        return lock.isValid();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        IOException failure = null;
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            channel.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw new IllegalStateException("failed to release business backend instance lock", failure);
        }
        closed = true;
    }

    private static IllegalStateException alreadyRunning() {
        return new IllegalStateException("business backend is already running");
    }

    private static void rejectLinksInExistingPath(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path segment : path) {
            current = current == null ? segment : current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(current) || attributes.isOther()) {
                throw new IllegalArgumentException("business backend lock path must not contain a link");
            }
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Preserve the original acquisition failure.
        }
    }
}
