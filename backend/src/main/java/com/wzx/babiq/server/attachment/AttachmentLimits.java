package com.wzx.babiq.server.attachment;

public final class AttachmentLimits {

    public static final int MAX_ATTACHMENTS = 8;
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    public static final int MAX_PATH_CHARACTERS = 4096;
    public static final int MAX_FILE_NAME_CHARACTERS = 255;
    public static final int MAX_IMAGE_SIDE = 16_384;
    public static final long MAX_IMAGE_PIXELS = 50_000_000L;

    private AttachmentLimits() {
    }
}
