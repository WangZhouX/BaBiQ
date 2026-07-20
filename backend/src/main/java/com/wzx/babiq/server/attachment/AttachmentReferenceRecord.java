package com.wzx.babiq.server.attachment;

/**
 * Narrow persistence projection used by controlled clipboard retention.
 *
 * @param payloadJson persisted user-message payload
 * @param archivedAt joined thread archive timestamp, or {@code null} for an active thread
 */
public record AttachmentReferenceRecord(String payloadJson, String archivedAt) {

    @Override
    public String toString() {
        return "AttachmentReferenceRecord[payloadJson=<redacted>, archivedAt=%s]"
                .formatted(archivedAt);
    }
}
