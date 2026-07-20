package com.wzx.babiq.server.attachment;

import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates a complete new-attachment request before any Turn or item mutation.
 */
@Service
public final class AttachmentPreparationService {

    private static final Pattern DISPLAY_ID_PATTERN = Pattern.compile("A-[A-HJ-NP-Z2-9]{6}");

    private final AttachmentFileValidator validator;
    private final Path controlledClipboardRoot;

    public AttachmentPreparationService(
            AttachmentFileValidator validator,
            BusinessDesktopModeProperties properties
    ) {
        this(
                validator,
                properties != null && properties.enabled()
                        ? properties.attachmentClipboardRoot()
                        : null);
    }

    AttachmentPreparationService(AttachmentFileValidator validator, Path controlledClipboardRoot) {
        this.validator = validator;
        this.controlledClipboardRoot = controlledClipboardRoot == null
                ? null
                : controlledClipboardRoot.toAbsolutePath().normalize();
    }

    public PreparedTurnInput prepareNew(String text, List<AttachmentRequest> requests) {
        List<AttachmentRequest> normalizedRequests = requests == null ? List.of() : List.copyOf(requests);
        validateRequestIdentities(normalizedRequests);

        List<PreparedAttachment> prepared = new ArrayList<>(normalizedRequests.size());
        long totalBytes = 0;
        for (AttachmentRequest request : normalizedRequests) {
            PreparedAttachment attachment = validator.validate(request);
            totalBytes = addWithoutOverflow(totalBytes, attachment.metadata().sizeBytes());
            if (totalBytes > AttachmentLimits.MAX_TOTAL_BYTES) {
                throw new AttachmentException(
                        AttachmentErrorCode.ATTACHMENT_TOTAL_TOO_LARGE,
                        "本轮附件总大小超过 50 MiB 上限");
            }
            AttachmentSource source = isTrustedClipboardImage(attachment)
                    ? AttachmentSource.CLIPBOARD_IMAGE
                    : AttachmentSource.SELECTED_FILE;
            prepared.add(attachment.withSource(source));
        }
        return new PreparedTurnInput(text, prepared, List.of());
    }

    private static void validateRequestIdentities(List<AttachmentRequest> requests) {
        if (requests.size() > AttachmentLimits.MAX_ATTACHMENTS) {
            throw new AttachmentException(
                    AttachmentErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                    "单轮最多选择 8 个附件");
        }

        Set<String> ids = new HashSet<>();
        Set<String> displayIds = new HashSet<>();
        for (AttachmentRequest request : requests) {
            if (request == null) {
                throw new AttachmentException(
                        AttachmentErrorCode.ATTACHMENT_EMPTY,
                        "附件描述不能为空");
            }
            String normalizedId = normalizeUuid(request.id());
            String displayId = request.displayId();
            if (displayId == null || !DISPLAY_ID_PATTERN.matcher(displayId).matches()) {
                throw new AttachmentException(
                        AttachmentErrorCode.ATTACHMENT_PATH_INVALID,
                        "附件显示标识无效");
            }
            if (!ids.add(normalizedId)
                    || !displayIds.add(displayId.toUpperCase(Locale.ROOT))) {
                throw new AttachmentException(
                        AttachmentErrorCode.ATTACHMENT_REFERENCE_AMBIGUOUS,
                        "附件标识重复，请重新选择附件");
            }
        }
    }

    private static String normalizeUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new AttachmentException(
                    AttachmentErrorCode.ATTACHMENT_PATH_INVALID,
                    "附件 ID 无效");
        }
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return uuid.toString();
        } catch (IllegalArgumentException exception) {
            throw new AttachmentException(
                    AttachmentErrorCode.ATTACHMENT_PATH_INVALID,
                    "附件 ID 无效");
        }
    }

    private boolean isTrustedClipboardImage(PreparedAttachment attachment) {
        return controlledClipboardRoot != null
                && attachment.canonicalPath().startsWith(controlledClipboardRoot)
                && attachment.metadata().mediaType().startsWith("image/");
    }

    private static long addWithoutOverflow(long left, long right) {
        if (right < 0 || left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
