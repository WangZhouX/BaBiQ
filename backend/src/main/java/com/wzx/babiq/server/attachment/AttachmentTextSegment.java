package com.wzx.babiq.server.attachment;

import java.util.Objects;

/**
 * Ephemeral, untrusted text extracted from one attachment for the current turn only.
 */
public record AttachmentTextSegment(
        String attachmentId,
        String displayId,
        String name,
        String mediaType,
        String text
) {

    public AttachmentTextSegment {
        attachmentId = Objects.requireNonNull(attachmentId, "attachmentId");
        displayId = Objects.requireNonNull(displayId, "displayId");
        name = Objects.requireNonNull(name, "name");
        mediaType = Objects.requireNonNull(mediaType, "mediaType");
        text = Objects.requireNonNull(text, "text");
    }

    public int originalCharacterCount() {
        return text.length();
    }

    @Override
    public String toString() {
        return "AttachmentTextSegment[attachmentId=%s, displayId=%s, name=<redacted>, "
                .formatted(attachmentId, displayId)
                + "mediaType=%s, originalCharacterCount=%d, text=<redacted>]"
                .formatted(mediaType, originalCharacterCount());
    }
}
