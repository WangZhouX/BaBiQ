package com.wzx.babiq.server.business.upload;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Owner-only file storage for short-lived OA resource bytes; SQLite stores only the opaque file name. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessResourceBlobStore {
    private final Path root;
    private final DeleteOperation deleteOperation;

    @Autowired
    public BusinessResourceBlobStore(
            @Value("${babiq.business.runtime-dir}/resources") String configuredRoot) {
        this(Path.of(configuredRoot));
    }

    BusinessResourceBlobStore(Path configuredRoot) {
        this(configuredRoot, Files::deleteIfExists);
    }

    BusinessResourceBlobStore(Path configuredRoot, DeleteOperation deleteOperation) {
        this.root = Objects.requireNonNull(configuredRoot, "configuredRoot").toAbsolutePath().normalize();
        this.deleteOperation = Objects.requireNonNull(deleteOperation, "deleteOperation");
    }

    String store(byte[] bytes) {
        Path file = null;
        try {
            file = BusinessUploadPathGuard.createStagedFile(root);
            try (var channel = BusinessUploadPathGuard.openForWrite(file)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            return file.getFileName().toString();
        } catch (IOException failure) {
            if (file != null) delete(file.getFileName().toString());
            throw new IllegalStateException("resource storage is unavailable", failure);
        }
    }

    byte[] load(String storageRef, long expectedLength) {
        Path file = resolve(storageRef);
        try {
            BusinessUploadPathGuard.verifySame(BusinessUploadPathGuard.capture(file));
            long size = Files.size(file);
            if (size != expectedLength || size <= 0 || size >= BusinessResourceHandleRegistry.MAX_BYTES) {
                throw new IllegalStateException("resource storage length mismatch");
            }
            return Files.readAllBytes(file);
        } catch (IOException failure) {
            throw new IllegalStateException("resource storage is unavailable", failure);
        }
    }

    boolean delete(String storageRef) {
        try {
            Path file = resolvePath(storageRef);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return true;
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
            BusinessUploadPathGuard.Identity identity = BusinessUploadPathGuard.capture(file);
            BusinessUploadPathGuard.verifySame(identity);
            return deleteOperation.delete(file) || !Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | IllegalArgumentException ignored) {
            return false;
        }
    }

    Set<String> storageRefs() {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return Set.of();
        try (var files = Files.list(root)) {
            return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> path.getFileName().toString())
                    .filter(BusinessResourceBlobStore::isOpaqueStorageRef)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException ignored) {
            return Set.of();
        }
    }

    private Path resolve(String storageRef) {
        Path file = resolvePath(storageRef);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("resource storage is unavailable");
        }
        return file;
    }

    private Path resolvePath(String storageRef) {
        if (!isOpaqueStorageRef(storageRef)) {
            throw new IllegalArgumentException("invalid resource storage reference");
        }
        Path file = root.resolve(storageRef).toAbsolutePath().normalize();
        if (!file.getParent().equals(root)) {
            throw new IllegalArgumentException("resource storage escaped root");
        }
        return file;
    }

    private static boolean isOpaqueStorageRef(String storageRef) {
        return storageRef != null
                && storageRef.startsWith("upload-")
                && storageRef.endsWith(".part")
                && !storageRef.contains("/")
                && !storageRef.contains("\\")
                && !storageRef.contains("://");
    }

    @FunctionalInterface
    interface DeleteOperation {
        boolean delete(Path path) throws IOException;
    }
}
