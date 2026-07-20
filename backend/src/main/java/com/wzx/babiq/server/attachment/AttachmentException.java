package com.wzx.babiq.server.attachment;

import java.util.Objects;

/**
 * Attachment failure carrying only a stable code and a user-safe message.
 */
public final class AttachmentException extends RuntimeException {

    private final AttachmentErrorCode code;
    private final String safeMessage;

    public AttachmentException(AttachmentErrorCode code, String safeMessage) {
        super(requireSafeMessage(safeMessage));
        this.code = Objects.requireNonNull(code, "code");
        this.safeMessage = safeMessage;
    }

    public AttachmentErrorCode code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }

    private static String requireSafeMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        return value;
    }
}
