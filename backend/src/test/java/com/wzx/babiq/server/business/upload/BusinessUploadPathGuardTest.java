package com.wzx.babiq.server.business.upload;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessUploadPathGuardTest {
    @Test
    void creates_owner_only_regular_file_and_rejects_link_in_directory_chain() throws Exception {
        Path runtime = Files.createTempDirectory("business-upload-path");
        Path root = runtime.resolve("attachments").resolve("uploads");
        Path staged = BusinessUploadPathGuard.createStagedFile(root);
        assertThat(Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)).isTrue();
        PosixFileAttributeView posix = Files.getFileAttributeView(staged, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            assertThat(Files.getPosixFilePermissions(staged, LinkOption.NOFOLLOW_LINKS))
                    .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } else {
            var owner = Files.getOwner(staged, LinkOption.NOFOLLOW_LINKS);
            assertThat(Files.getFileAttributeView(staged, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).getAcl()).allMatch(entry ->
                    entry.principal().equals(owner));
        }

        Path linkedRoot = runtime.resolve("linked");
        try {
            Files.createSymbolicLink(linkedRoot, root);
            assertThatThrownBy(() -> BusinessUploadPathGuard.createStagedFile(linkedRoot))
                    .isInstanceOf(IllegalArgumentException.class);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
            // The ACL/regular-file assertion above still exercises the Windows path.
        }
        Files.deleteIfExists(staged);
    }
}
