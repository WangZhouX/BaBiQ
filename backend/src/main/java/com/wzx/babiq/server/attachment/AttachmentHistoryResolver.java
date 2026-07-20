package com.wzx.babiq.server.attachment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves stable attachment display IDs exclusively from one authorized thread history.
 */
@Component
public final class AttachmentHistoryResolver {

    static final int HISTORY_PAGE_SIZE = 200;
    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
            "(?i)(?<![A-Z0-9])A-[A-HJ-NP-Z2-9]{6}(?![A-Z0-9])");
    private static final Pattern DISPLAY_ID_PATTERN = Pattern.compile("A-[A-HJ-NP-Z2-9]{6}");

    private final ConversationRepository repository;
    private final ObjectMapper objectMapper;
    private final AttachmentFileValidator validator;

    public AttachmentHistoryResolver(
            ConversationRepository repository,
            ObjectMapper objectMapper,
            AttachmentFileValidator validator
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * Checks new attachment identity collisions and resolves explicit history references.
     *
     * <p>All history reads use the frozen business scope supplied by the request boundary.</p>
     */
    public PreparedTurnInput resolve(
            String threadId,
            BusinessIdentityScope scope,
            PreparedTurnInput preparedNew
    ) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(preparedNew, "preparedNew");
        BusinessIdentityScope effectiveScope = scope == null
                ? BusinessIdentityScope.UNSCOPED
                : scope;
        HistoryIndex history = indexHistory(threadId, effectiveScope);
        rejectNewCollisions(preparedNew.newAttachments(), history);

        Map<String, PreparedAttachment> referencedById = new LinkedHashMap<>();
        for (PreparedAttachment attachment : preparedNew.referencedAttachments()) {
            referencedById.putIfAbsent(attachment.metadata().id(), attachment);
        }
        AttachmentBudget persistedBudget = AttachmentBudget.from(preparedNew.allAttachments());
        for (String displayId : referencedDisplayIds(preparedNew.text())) {
            AttachmentMetadata persisted = history.byDisplayId().get(displayId);
            if (persisted == null) {
                continue;
            }
            if (!referencedById.containsKey(persisted.id())) {
                persistedBudget.include(persisted);
            }
            PreparedAttachment referenced = revalidate(persisted);
            referencedById.putIfAbsent(referenced.metadata().id(), referenced);
        }
        PreparedTurnInput resolved = new PreparedTurnInput(
                preparedNew.text(),
                preparedNew.newAttachments(),
                List.copyOf(referencedById.values()));
        validateCombinedLimits(resolved.allAttachments());
        return resolved;
    }

    private HistoryIndex indexHistory(String threadId, BusinessIdentityScope scope) {
        Map<String, AttachmentMetadata> byUuid = new LinkedHashMap<>();
        Map<String, AttachmentMetadata> byDisplayId = new LinkedHashMap<>();
        String beforeItemId = null;
        Set<String> visitedCursors = new HashSet<>();
        while (true) {
            List<ItemRecord> page = repository.listItems(
                    threadId, HISTORY_PAGE_SIZE, beforeItemId, scope);
            if (page == null || page.isEmpty()) {
                break;
            }
            for (ItemRecord record : page) {
                if (record == null || !"userMessage".equals(record.type())) {
                    continue;
                }
                UserMessageItem item = decodeUserMessage(record);
                for (AttachmentMetadata metadata : item.attachments()) {
                    index(metadata, byUuid, byDisplayId);
                }
            }
            if (page.size() < HISTORY_PAGE_SIZE) {
                break;
            }
            ItemRecord earliest = page.get(0);
            String nextCursor = earliest == null ? null : earliest.itemId();
            if (nextCursor == null || nextCursor.isBlank() || !visitedCursors.add(nextCursor)) {
                throw ambiguous("附件历史分页状态异常，请重新打开会话后重试");
            }
            beforeItemId = nextCursor;
        }
        return new HistoryIndex(Map.copyOf(byUuid), Map.copyOf(byDisplayId));
    }

    private UserMessageItem decodeUserMessage(ItemRecord record) {
        try {
            UserMessageItem item = objectMapper.readValue(record.payloadJson(), UserMessageItem.class);
            if (!Objects.equals(record.itemId(), item.id())
                    || !Objects.equals(record.type(), item.type())) {
                throw ambiguous("Attachment history envelope does not match its payload");
            }
            return item;
        } catch (AttachmentException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw ambiguous("附件历史元数据损坏，无法安全解析引用");
        }
    }

    private static void index(
            AttachmentMetadata metadata,
            Map<String, AttachmentMetadata> byUuid,
            Map<String, AttachmentMetadata> byDisplayId
    ) {
        if (metadata == null) {
            throw ambiguous("附件历史元数据损坏，无法安全解析引用");
        }
        String uuid = canonicalUuid(metadata.id());
        String displayId = canonicalDisplayId(metadata.displayId());
        if (byUuid.putIfAbsent(uuid, metadata) != null
                || byDisplayId.putIfAbsent(displayId, metadata) != null) {
            throw ambiguous("附件历史存在重复标识，无法确定引用目标");
        }
    }

    private static void rejectNewCollisions(
            List<PreparedAttachment> newAttachments,
            HistoryIndex history
    ) {
        for (PreparedAttachment attachment : newAttachments) {
            String uuid = canonicalUuid(attachment.metadata().id());
            String displayId = canonicalDisplayId(attachment.metadata().displayId());
            if (history.byUuid().containsKey(uuid)
                    || history.byDisplayId().containsKey(displayId)) {
                throw ambiguous("附件标识已在当前会话中使用，请重新选择附件");
            }
        }
    }

    private static void validateCombinedLimits(List<PreparedAttachment> attachments) {
        if (attachments.size() > AttachmentLimits.MAX_ATTACHMENTS) {
            throw new AttachmentException(
                    AttachmentErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                    "单轮最多使用 8 个附件");
        }
        long totalBytes = 0;
        for (PreparedAttachment attachment : attachments) {
            long sizeBytes = attachment.metadata().sizeBytes();
            totalBytes = sizeBytes < 0 || totalBytes > Long.MAX_VALUE - sizeBytes
                    ? Long.MAX_VALUE
                    : totalBytes + sizeBytes;
            if (totalBytes > AttachmentLimits.MAX_TOTAL_BYTES) {
                throw new AttachmentException(
                        AttachmentErrorCode.ATTACHMENT_TOTAL_TOO_LARGE,
                        "本轮附件总大小超过 50 MiB 上限");
            }
        }
    }

    private PreparedAttachment revalidate(AttachmentMetadata persisted) {
        PreparedAttachment current = validator.validate(new AttachmentRequest(
                persisted.id(),
                persisted.displayId(),
                persisted.name(),
                persisted.localPath()));
        AttachmentMetadata currentMetadata = current.metadata();
        if (currentMetadata.sizeBytes() != persisted.sizeBytes()
                || !currentMetadata.sha256().equalsIgnoreCase(persisted.sha256())
                || !currentMetadata.mediaType().equalsIgnoreCase(persisted.mediaType())
                || !currentMetadata.name().equals(persisted.name())) {
            throw new AttachmentException(
                    AttachmentErrorCode.ATTACHMENT_CHANGED,
                    "附件 " + persisted.displayId() + " 自发送后已变化，请重新选择");
        }
        return current.withSource(persisted.source());
    }

    private static List<String> referencedDisplayIds(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
        Matcher matcher = REFERENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            ordered.put(canonicalDisplayId(matcher.group()), Boolean.TRUE);
        }
        return List.copyOf(ordered.keySet());
    }

    private static String canonicalUuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return uuid.toString();
        } catch (RuntimeException exception) {
            throw ambiguous("附件历史包含无效 ID，无法安全解析引用");
        }
    }

    private static String canonicalDisplayId(String value) {
        if (value == null) {
            throw ambiguous("附件历史包含无效显示标识，无法安全解析引用");
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!DISPLAY_ID_PATTERN.matcher(normalized).matches()) {
            throw ambiguous("附件历史包含无效显示标识，无法安全解析引用");
        }
        return normalized;
    }

    private static AttachmentException ambiguous(String safeMessage) {
        return new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_REFERENCE_AMBIGUOUS,
                safeMessage);
    }

    private record HistoryIndex(
            Map<String, AttachmentMetadata> byUuid,
            Map<String, AttachmentMetadata> byDisplayId
    ) {
    }

    /**
     * Applies cheap persisted-metadata limits before any file-system read or hashing.
     * The authoritative limit check still runs after revalidation.
     */
    private static final class AttachmentBudget {

        private final Set<String> attachmentIds = new HashSet<>();
        private int count;
        private long totalBytes;

        static AttachmentBudget from(List<PreparedAttachment> attachments) {
            AttachmentBudget budget = new AttachmentBudget();
            for (PreparedAttachment attachment : attachments) {
                budget.include(attachment.metadata());
            }
            return budget;
        }

        void include(AttachmentMetadata metadata) {
            String attachmentId = canonicalUuid(metadata.id());
            if (!attachmentIds.add(attachmentId)) {
                return;
            }
            count++;
            if (count > AttachmentLimits.MAX_ATTACHMENTS) {
                throw new AttachmentException(
                        AttachmentErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                        "A turn can use at most 8 attachments");
            }
            totalBytes = saturatedAdd(totalBytes, metadata.sizeBytes());
            if (totalBytes > AttachmentLimits.MAX_TOTAL_BYTES) {
                throw new AttachmentException(
                        AttachmentErrorCode.ATTACHMENT_TOTAL_TOO_LARGE,
                        "The combined attachment size exceeds the 50 MiB limit");
            }
        }

        private static long saturatedAdd(long current, long increment) {
            if (increment < 0 || current > Long.MAX_VALUE - increment) {
                return Long.MAX_VALUE;
            }
            return current + increment;
        }
    }
}
