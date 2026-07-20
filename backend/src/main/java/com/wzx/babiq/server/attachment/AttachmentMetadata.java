package com.wzx.babiq.server.attachment;

import java.util.Objects;

/**
 * Persistable metadata for one locally validated attachment.
 */
public record AttachmentMetadata(
        String id,
        String displayId,
        String name,
        String localPath,
        String mediaType,
        long sizeBytes,
        String sha256,
        AttachmentSource source
) {

    public AttachmentMetadata {
        id = Objects.requireNonNull(id, "id");
        displayId = Objects.requireNonNull(displayId, "displayId");
        name = Objects.requireNonNull(name, "name");
        localPath = Objects.requireNonNull(localPath, "localPath");
        mediaType = Objects.requireNonNull(mediaType, "mediaType");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        source = Objects.requireNonNull(source, "source");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }

    public AttachmentMetadata withSource(AttachmentSource trustedSource) {
        return new AttachmentMetadata(
                id, displayId, name, localPath, mediaType, sizeBytes, sha256, trustedSource);
    }

    @Override
    public String toString() {
        return ("AttachmentMetadata[id=%s, displayId=%s, name=%s, localPath=<redacted>, "
                + "mediaType=%s, sizeBytes=%d, sha256=<redacted>, source=%s]")
                .formatted(id, displayId, name, mediaType, sizeBytes, source);
    }
}
