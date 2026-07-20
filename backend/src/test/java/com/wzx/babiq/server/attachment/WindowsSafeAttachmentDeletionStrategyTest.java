package com.wzx.babiq.server.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs(OS.WINDOWS)
class WindowsSafeAttachmentDeletionStrategyTest {

    @TempDir
    Path tempDir;

    @Test
    void nativeIdentityFollowsTheObjectAcrossRenameAndRejectsSamePathReplacement()
            throws Exception {
        WindowsSafeAttachmentDeletionStrategy strategy =
                new WindowsSafeAttachmentDeletionStrategy();
        Path originalPath = Files.write(tempDir.resolve("attachment.png"), new byte[]{1, 2, 3});

        WindowsSafeAttachmentDeletionStrategy.NativeFileIdentity original =
                strategy.readIdentity(originalPath);
        Path movedPath = Files.move(originalPath, tempDir.resolve("moved.png"));
        WindowsSafeAttachmentDeletionStrategy.NativeFileIdentity moved =
                strategy.readIdentity(movedPath);
        Files.write(originalPath, new byte[]{1, 2, 3});
        WindowsSafeAttachmentDeletionStrategy.NativeFileIdentity replacement =
                strategy.readIdentity(originalPath);

        assertThat(original).isEqualTo(moved);
        assertThat(replacement).isNotEqualTo(original);
        assertThat(original.toString()).doesNotContain(tempDir.toString());
    }

    @Test
    void candidateHandleBlocksReplacementAndDeletesTheAnchoredObject() throws Exception {
        WindowsSafeAttachmentDeletionStrategy strategy =
                new WindowsSafeAttachmentDeletionStrategy();
        Path candidate = Files.write(tempDir.resolve("candidate.png"), new byte[]{1, 2, 3});

        try (WindowsSafeAttachmentDeletionStrategy.RootLease root =
                     strategy.openRoot(tempDir);
             WindowsSafeAttachmentDeletionStrategy.CandidateLease lease =
                     root.openCandidate(candidate)) {
            assertThatThrownBy(() -> Files.move(candidate, tempDir.resolve("replacement-window.png")))
                    .isInstanceOf(java.io.IOException.class);

            assertThat(lease.deleteIfUnchanged()).isTrue();
            assertThat(lease.deleteIfUnchanged()).isFalse();
            lease.close();
            lease.close();
        }

        assertThat(candidate).doesNotExist();
    }

    @Test
    void nativeFailuresDoNotExposeTheInputPath() {
        WindowsSafeAttachmentDeletionStrategy strategy =
                new WindowsSafeAttachmentDeletionStrategy();
        Path missing = tempDir.resolve("private-name.png");

        assertThatThrownBy(() -> strategy.readIdentity(missing))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageNotContaining(tempDir.toString())
                .hasMessageNotContaining("private-name.png");
    }
}
