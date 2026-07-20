package com.wzx.babiq.server.attachment;

import java.util.List;

/**
 * Reads only the persisted user-message data required to retain controlled screenshots.
 */
@FunctionalInterface
public interface AttachmentReferenceRepository {

    List<AttachmentReferenceRecord> findAll();
}
