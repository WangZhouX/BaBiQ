package com.wzx.babiq.server.attachment;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/**
 * Internal immutable attachment with canonical path and file identity for later revalidation.
 */
public record PreparedAttachment(
        AttachmentMetadata metadata,
        Path canonicalPath,
        FileIdentity identity
) {

    public PreparedAttachment {
        metadata = Objects.requireNonNull(metadata, "metadata");
        canonicalPath = Objects.requireNonNull(canonicalPath, "canonicalPath")
                .toAbsolutePath()
                .normalize();
        identity = Objects.requireNonNull(identity, "identity");
    }

    public PreparedAttachment withSource(AttachmentSource trustedSource) {
        return new PreparedAttachment(metadata.withSource(trustedSource), canonicalPath, identity);
    }

    @Override
    public String toString() {
        return "PreparedAttachment[id=%s, displayId=%s, canonicalPath=<redacted>, identity=%s]"
                .formatted(metadata.id(), metadata.displayId(), identity);
    }

    public record FileIdentity(
            long sizeBytes,
            FileTime lastModifiedTime,
            String fileKey
    ) {

        public FileIdentity {
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
            lastModifiedTime = Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        }
    }
}
