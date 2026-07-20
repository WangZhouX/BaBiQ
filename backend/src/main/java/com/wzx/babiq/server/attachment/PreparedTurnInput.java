package com.wzx.babiq.server.attachment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One validated Turn input, retaining provenance between new and history-referenced attachments.
 */
public record PreparedTurnInput(
        String text,
        List<PreparedAttachment> newAttachments,
        List<PreparedAttachment> referencedAttachments
) {

    public PreparedTurnInput {
        text = text == null ? "" : text;
        newAttachments = List.copyOf(Objects.requireNonNull(newAttachments, "newAttachments"));
        referencedAttachments = List.copyOf(
                Objects.requireNonNull(referencedAttachments, "referencedAttachments"));
    }

    public List<PreparedAttachment> allAttachments() {
        Map<String, PreparedAttachment> ordered = new LinkedHashMap<>();
        for (PreparedAttachment attachment : newAttachments) {
            ordered.putIfAbsent(attachment.metadata().id(), attachment);
        }
        for (PreparedAttachment attachment : referencedAttachments) {
            ordered.putIfAbsent(attachment.metadata().id(), attachment);
        }
        return List.copyOf(new ArrayList<>(ordered.values()));
    }

    @Override
    public String toString() {
        return "PreparedTurnInput[textLength=%d, newAttachmentIds=%s, referencedAttachmentIds=%s]"
                .formatted(
                        text.length(),
                        newAttachments.stream().map(item -> item.metadata().id()).toList(),
                        referencedAttachments.stream().map(item -> item.metadata().id()).toList());
    }
}
