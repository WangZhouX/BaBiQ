package com.wzx.babiq.server.business.upload;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Creates upload staging files without following links/reparse points and restricts them to the owner. */
final class BusinessUploadPathGuard {
    private BusinessUploadPathGuard() { }

    static Path createStagedFile(Path configuredRoot) throws IOException {
        Path root = configuredRoot.toAbsolutePath().normalize();
        rejectExistingLinks(root);
        Files.createDirectories(root);
        rejectExistingLinks(root);
        BasicFileAttributes rootAttributes = attributes(root);
        if (!rootAttributes.isDirectory() || rootAttributes.isOther()) {
            throw new IllegalArgumentException("upload root is not a regular directory");
        }
        applyOwnerOnly(root, true);
        Path file = Files.createTempFile(root, "upload-", ".part").toAbsolutePath().normalize();
        if (!file.getParent().equals(root)) throw new IllegalArgumentException("upload file escaped root");
        requireRegular(file);
        applyOwnerOnly(file, false);
        return file;
    }

    static FileChannel openForWrite(Path file) throws IOException {
        requireRegular(file);
        return FileChannel.open(file, Set.<OpenOption>of(
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS));
    }

    static Identity capture(Path file) throws IOException {
        BasicFileAttributes attributes = requireRegular(file);
        return new Identity(file.toAbsolutePath().normalize(), attributes.fileKey(), attributes.creationTime().toMillis());
    }

    static void verifySame(Identity expected) throws IOException {
        Identity current = capture(expected.path());
        if (expected.fileKey() != null && current.fileKey() != null
                && !expected.fileKey().equals(current.fileKey())
                || expected.creationMillis() != current.creationMillis()) {
            throw new IllegalArgumentException("upload staging file changed");
        }
    }

    private static BasicFileAttributes requireRegular(Path file) throws IOException {
        BasicFileAttributes attributes = attributes(file);
        if (Files.isSymbolicLink(file) || attributes.isOther() || !attributes.isRegularFile()) {
            throw new IllegalArgumentException("upload staging file is not regular");
        }
        return attributes;
    }

    private static void rejectExistingLinks(Path path) throws IOException {
        Path current = path.getRoot();
        rejectLink(current);
        for (Path segment : path) {
            current = current == null ? segment : current.resolve(segment);
            rejectLink(current);
        }
    }

    private static void rejectLink(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attributes = attributes(path);
        if (Files.isSymbolicLink(path) || attributes.isOther()) {
            throw new IllegalArgumentException("upload path contains a link or reparse point");
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void applyOwnerOnly(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Files.setPosixFilePermissions(path, directory
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("owner-only file permissions are unavailable");
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        acl.setAcl(List.of(entry));
    }

    record Identity(Path path, Object fileKey, long creationMillis) { }
}
