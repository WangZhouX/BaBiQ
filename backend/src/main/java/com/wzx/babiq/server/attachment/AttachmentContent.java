package com.wzx.babiq.server.attachment;

import java.util.Arrays;
import java.util.Objects;

/**
 * Ephemeral content loaded for one model invocation.
 */
public final class AttachmentContent {

    private final PreparedAttachment attachment;
    private final AttachmentTextSegment textSegment;
    private final byte[] imageBytes;

    private AttachmentContent(
            PreparedAttachment attachment,
            AttachmentTextSegment textSegment,
            byte[] imageBytes
    ) {
        this.attachment = Objects.requireNonNull(attachment, "attachment");
        this.textSegment = textSegment;
        this.imageBytes = imageBytes == null ? null : imageBytes.clone();
        if ((textSegment == null) == (imageBytes == null)) {
            throw new IllegalArgumentException("exactly one attachment content kind is required");
        }
    }

    public static AttachmentContent document(
            PreparedAttachment attachment,
            AttachmentTextSegment textSegment
    ) {
        return new AttachmentContent(
                attachment,
                Objects.requireNonNull(textSegment, "textSegment"),
                null);
    }

    public static AttachmentContent image(PreparedAttachment attachment, byte[] imageBytes) {
        Objects.requireNonNull(imageBytes, "imageBytes");
        return new AttachmentContent(attachment, null, imageBytes);
    }

    public PreparedAttachment attachment() {
        return attachment;
    }

    public AttachmentTextSegment textSegment() {
        return textSegment;
    }

    public byte[] imageBytes() {
        return imageBytes == null ? null : imageBytes.clone();
    }

    public boolean isImage() {
        return imageBytes != null;
    }

    @Override
    public String toString() {
        return "AttachmentContent[id=%s, displayId=%s, mediaType=%s, kind=%s, "
                .formatted(
                        attachment.metadata().id(),
                        attachment.metadata().displayId(),
                        attachment.metadata().mediaType(),
                        isImage() ? "IMAGE" : "TEXT")
                + "content=<redacted>]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AttachmentContent that)) {
            return false;
        }
        return attachment.equals(that.attachment)
                && Objects.equals(textSegment, that.textSegment)
                && Arrays.equals(imageBytes, that.imageBytes);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(attachment, textSegment) + Arrays.hashCode(imageBytes);
    }
}
