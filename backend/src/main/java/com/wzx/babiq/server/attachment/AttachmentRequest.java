package com.wzx.babiq.server.attachment;

/**
 * Local attachment descriptor received from the trusted desktop transport.
 *
 * <p>The path remains data, not authority: the backend validates the filesystem object before use.</p>
 */
public record AttachmentRequest(
        String id,
        String displayId,
        String name,
        String localPath
) {

    @Override
    public String toString() {
        return "AttachmentRequest[id=%s, displayId=%s, name=<redacted>, localPath=<redacted>]"
                .formatted(id, displayId);
    }
}
