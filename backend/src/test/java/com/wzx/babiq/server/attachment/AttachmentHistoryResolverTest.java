package com.wzx.babiq.server.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentHistoryResolverTest {

    private static final BusinessIdentityScope SCOPE = BusinessIdentityScope.scoped(
            "desktop", "session", "auth", 1, "user", "tenant", "platform");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolves_case_insensitive_standalone_reference_from_all_scoped_history_pages() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        AttachmentMetadata historical = metadata(
                "550e8400-e29b-41d4-a716-446655440000", "A-7K3M2Q", "合同.pdf", "a".repeat(64));
        PreparedAttachment revalidated = prepared(historical);
        when(validator.validate(any())).thenReturn(revalidated);
        List<ItemRecord> latestPage = fullPage("thread-a", 200);
        ItemRecord older = userRecord("thread-a", 1, historical);
        when(repository.listItems(eq("thread-a"), anyInt(), isNull(), eq(SCOPE)))
                .thenReturn(latestPage);
        when(repository.listItems(eq("thread-a"), anyInt(), eq(latestPage.get(0).itemId()), eq(SCOPE)))
                .thenReturn(List.of(older));
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);
        PreparedTurnInput newInput = new PreparedTurnInput(
                "请重新审阅 a-7k3m2q；但 XA-7K3M2QY 不是引用",
                List.of(),
                List.of());

        PreparedTurnInput resolved = resolver.resolve("thread-a", SCOPE, newInput);

        assertThat(resolved.referencedAttachments()).containsExactly(revalidated);
        assertThat(resolved.allAttachments()).containsExactly(revalidated);
        ArgumentCaptor<AttachmentRequest> request = ArgumentCaptor.forClass(AttachmentRequest.class);
        verify(validator).validate(request.capture());
        assertThat(request.getValue().id()).isEqualTo(historical.id());
        assertThat(request.getValue().displayId()).isEqualTo("A-7K3M2Q");
        verify(repository, never()).listItems(eq("thread-b"), anyInt(), any(), any());
    }

    @Test
    void does_not_resolve_substrings_or_unknown_and_never_scans_another_thread() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        AttachmentMetadata historical = metadata(
                "550e8400-e29b-41d4-a716-446655440000", "A-7K3M2Q", "合同.pdf", "a".repeat(64));
        when(repository.listItems("thread-a", 200, null, SCOPE))
                .thenReturn(List.of(userRecord("thread-a", 1, historical)));
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);

        PreparedTurnInput resolved = resolver.resolve(
                "thread-a",
                SCOPE,
                new PreparedTurnInput(
                        "XA-7K3M2QY 和 A-92CD4F 都不应读取该附件",
                        List.of(),
                        List.of()));

        assertThat(resolved.referencedAttachments()).isEmpty();
        verify(validator, never()).validate(any());
        verify(repository, never()).listItems(eq("thread-b"), anyInt(), any(), any());
    }

    @Test
    void preserves_new_then_text_reference_order_and_deduplicates_repeated_reference() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        PreparedAttachment firstReference = prepared(metadata(
                "550e8400-e29b-41d4-a716-446655440001", "A-7K3M2Q", "one.pdf", "a".repeat(64)));
        PreparedAttachment secondReference = prepared(metadata(
                "550e8400-e29b-41d4-a716-446655440002", "A-92CD4F", "two.pdf", "b".repeat(64)));
        PreparedAttachment selected = prepared(metadata(
                "550e8400-e29b-41d4-a716-446655440003", "A-Q2W3E4", "new.pdf", "c".repeat(64)));
        PreparedAttachment alreadyReferenced = prepared(metadata(
                "550e8400-e29b-41d4-a716-446655440004", "A-R2W3E4", "existing.pdf", "d".repeat(64)));
        when(repository.listItems("thread-a", 200, null, SCOPE)).thenReturn(List.of(
                userRecord("thread-a", 1, firstReference.metadata()),
                userRecord("thread-a", 2, secondReference.metadata())));
        when(validator.validate(any())).thenAnswer(invocation -> {
            AttachmentRequest request = invocation.getArgument(0);
            return request.displayId().equals("A-92CD4F") ? secondReference : firstReference;
        });
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);

        PreparedTurnInput resolved = resolver.resolve(
                "thread-a",
                SCOPE,
                new PreparedTurnInput(
                        "先 A-92CD4F，再 A-7K3M2Q，最后重复 a-92cd4f",
                        List.of(selected),
                        List.of(alreadyReferenced)));

        assertThat(resolved.newAttachments()).containsExactly(selected);
        assertThat(resolved.referencedAttachments())
                .containsExactly(alreadyReferenced, secondReference, firstReference);
        assertThat(resolved.allAttachments())
                .containsExactly(selected, alreadyReferenced, secondReference, firstReference);
    }

    @Test
    void rejects_new_uuid_or_display_id_collision_against_any_paginated_history() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        AttachmentMetadata historical = metadata(
                "550e8400-e29b-41d4-a716-446655440000", "A-7K3M2Q", "old.pdf", "a".repeat(64));
        when(repository.listItems("thread-a", 200, null, SCOPE))
                .thenReturn(List.of(userRecord("thread-a", 1, historical)));
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);
        PreparedAttachment uuidCollision = prepared(metadata(
                historical.id(), "A-92CD4F", "new.pdf", "b".repeat(64)));
        PreparedAttachment displayCollision = prepared(metadata(
                "550e8400-e29b-41d4-a716-446655440099", "A-7K3M2Q", "newer.pdf", "c".repeat(64)));

        assertAmbiguous(() -> resolver.resolve(
                "thread-a", SCOPE, new PreparedTurnInput("", List.of(uuidCollision), List.of())));
        assertAmbiguous(() -> resolver.resolve(
                "thread-a", SCOPE, new PreparedTurnInput("", List.of(displayCollision), List.of())));
        verify(validator, never()).validate(any());
    }

    @Test
    void rejects_corrupted_history_with_duplicate_display_ids_as_ambiguous() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        AttachmentMetadata first = metadata(
                "550e8400-e29b-41d4-a716-446655440001", "A-7K3M2Q", "one.pdf", "a".repeat(64));
        AttachmentMetadata second = metadata(
                "550e8400-e29b-41d4-a716-446655440002", "A-7K3M2Q", "two.pdf", "b".repeat(64));
        when(repository.listItems("thread-a", 200, null, SCOPE)).thenReturn(List.of(
                userRecord("thread-a", 1, first),
                userRecord("thread-a", 2, second)));
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);

        assertAmbiguous(() -> resolver.resolve(
                "thread-a",
                SCOPE,
                new PreparedTurnInput("读取 A-7K3M2Q", List.of(), List.of())));
        verify(validator, never()).validate(any());
    }

    @Test
    void rejects_a_referenced_file_when_its_current_hash_differs_from_persisted_metadata() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        AttachmentMetadata historical = metadata(
                "550e8400-e29b-41d4-a716-446655440000", "A-7K3M2Q", "old.pdf", "a".repeat(64));
        AttachmentMetadata changed = metadata(
                historical.id(), historical.displayId(), "old.pdf", "b".repeat(64));
        when(repository.listItems("thread-a", 200, null, SCOPE))
                .thenReturn(List.of(userRecord("thread-a", 1, historical)));
        when(validator.validate(any())).thenReturn(prepared(changed));
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);

        assertThatThrownBy(() -> resolver.resolve(
                "thread-a",
                SCOPE,
                new PreparedTurnInput("读取 A-7K3M2Q", List.of(), List.of())))
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code()).isEqualTo(AttachmentErrorCode.ATTACHMENT_CHANGED));
    }

    @Test
    void rejects_reference_only_count_above_the_per_turn_limit() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        List<AttachmentMetadata> history = attachments(0, AttachmentLimits.MAX_ATTACHMENTS + 1, 42);
        when(repository.listItems("thread-a", 200, null, SCOPE))
                .thenReturn(List.of(userRecord("thread-a", 1, history.toArray(AttachmentMetadata[]::new))));
        stubRevalidation(validator, history);
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);

        assertCode(
                () -> resolver.resolve(
                        "thread-a", SCOPE,
                        new PreparedTurnInput(referenceText(history), List.of(), List.of())),
                AttachmentErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
        verify(validator, times(AttachmentLimits.MAX_ATTACHMENTS)).validate(any());
        verify(validator, never()).validate(org.mockito.ArgumentMatchers.argThat(request ->
                history.get(AttachmentLimits.MAX_ATTACHMENTS).displayId().equals(request.displayId())));
    }

    @Test
    void rejects_combined_new_and_referenced_count_above_the_per_turn_limit() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        List<AttachmentMetadata> history = attachments(0, AttachmentLimits.MAX_ATTACHMENTS, 42);
        when(repository.listItems("thread-a", 200, null, SCOPE))
                .thenReturn(List.of(userRecord("thread-a", 1, history.toArray(AttachmentMetadata[]::new))));
        stubRevalidation(validator, history);
        PreparedAttachment selected = prepared(metadata(20, 42));
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);

        assertCode(
                () -> resolver.resolve(
                        "thread-a", SCOPE,
                        new PreparedTurnInput(referenceText(history), List.of(selected), List.of())),
                AttachmentErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
    }

    @Test
    void rejects_combined_new_and_referenced_bytes_before_revalidating_the_over_budget_reference()
            throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        AttachmentMetadata historical = metadata(0, 21L * 1024 * 1024);
        when(repository.listItems("thread-a", 200, null, SCOPE))
                .thenReturn(List.of(userRecord("thread-a", 1, historical)));
        stubRevalidation(validator, List.of(historical));
        PreparedAttachment selected = prepared(metadata(20, 30L * 1024 * 1024));
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);

        assertCode(
                () -> resolver.resolve(
                        "thread-a", SCOPE,
                        new PreparedTurnInput(
                                historical.displayId(), List.of(selected), List.of())),
                AttachmentErrorCode.ATTACHMENT_TOTAL_TOO_LARGE);
        verify(validator, never()).validate(any());
    }

    @Test
    void saturates_persisted_reference_bytes_on_long_overflow_before_revalidation() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        AttachmentMetadata huge = metadata(1, Long.MAX_VALUE);
        AttachmentMetadata extra = metadata(2, 1);
        when(repository.listItems("thread-b", 200, null, SCOPE))
                .thenReturn(List.of(userRecord("thread-b", 2, huge, extra)));
        stubRevalidation(validator, List.of(huge, extra));
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);

        assertCode(
                () -> resolver.resolve(
                        "thread-b", SCOPE,
                        new PreparedTurnInput(
                                huge.displayId() + " " + extra.displayId(), List.of(), List.of())),
                AttachmentErrorCode.ATTACHMENT_TOTAL_TOO_LARGE);
        verify(validator, never()).validate(any());
    }

    @Test
    void deduplicates_the_same_reference_before_counting_combined_bytes() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        AttachmentMetadata historical = metadata(0, 30L * 1024 * 1024);
        PreparedAttachment alreadyReferenced = prepared(historical);
        when(repository.listItems("thread-a", 200, null, SCOPE))
                .thenReturn(List.of(userRecord("thread-a", 1, historical)));
        stubRevalidation(validator, List.of(historical));
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);

        PreparedTurnInput resolved = resolver.resolve(
                "thread-a",
                SCOPE,
                new PreparedTurnInput(
                        historical.displayId(), List.of(), List.of(alreadyReferenced)));

        assertThat(resolved.referencedAttachments()).containsExactly(alreadyReferenced);
        assertThat(resolved.allAttachments()).containsExactly(alreadyReferenced);
    }

    @Test
    void rejects_history_when_payload_id_does_not_match_the_item_record_envelope() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        UserMessageItem payload = new UserMessageItem(
                "payload-id", "userMessage", "old", List.of(metadata(0, 42)));
        ItemRecord mismatched = ItemRecord.of(
                "record-id", "thread-a", "turn-1", "userMessage", 1,
                objectMapper.writeValueAsString(payload), "completed", Instant.EPOCH);
        when(repository.listItems("thread-a", 200, null, SCOPE)).thenReturn(List.of(mismatched));
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);

        assertAmbiguous(() -> resolver.resolve(
                "thread-a", SCOPE, new PreparedTurnInput("A-234562", List.of(), List.of())));
        verify(validator, never()).validate(any());
    }

    @Test
    void rejects_history_when_payload_type_does_not_match_the_item_record_envelope() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        UserMessageItem payload = new UserMessageItem(
                "record-id", "tamperedType", "old", List.of(metadata(0, 42)));
        ItemRecord mismatched = ItemRecord.of(
                "record-id", "thread-a", "turn-1", "userMessage", 1,
                objectMapper.writeValueAsString(payload), "completed", Instant.EPOCH);
        when(repository.listItems("thread-a", 200, null, SCOPE)).thenReturn(List.of(mismatched));
        AttachmentHistoryResolver resolver = new AttachmentHistoryResolver(
                repository, objectMapper, validator);

        assertAmbiguous(() -> resolver.resolve(
                "thread-a", SCOPE, new PreparedTurnInput("A-234562", List.of(), List.of())));
        verify(validator, never()).validate(any());
    }

    private List<ItemRecord> fullPage(String threadId, int size) {
        List<ItemRecord> records = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            records.add(otherRecord(threadId, 1000 + index));
        }
        return records;
    }

    private ItemRecord userRecord(String threadId, int sequence, AttachmentMetadata... attachments)
            throws Exception {
        UserMessageItem item = new UserMessageItem(
                "it_user_" + sequence,
                "userMessage",
                "old",
                List.of(attachments));
        return ItemRecord.of(
                item.id(),
                threadId,
                "turn_" + sequence,
                item.type(),
                sequence,
                objectMapper.writeValueAsString(item),
                "completed",
                Instant.EPOCH.plusSeconds(sequence));
    }

    private ItemRecord otherRecord(String threadId, int sequence) {
        return ItemRecord.of(
                "it_other_" + sequence,
                threadId,
                "turn_" + sequence,
                "agentMessage",
                sequence,
                "{\"id\":\"it_other_" + sequence + "\",\"type\":\"agentMessage\",\"text\":\"ok\"}",
                "completed",
                Instant.EPOCH.plusSeconds(sequence));
    }

    private static AttachmentMetadata metadata(
            String id,
            String displayId,
            String name,
            String sha256
    ) {
        return new AttachmentMetadata(
                id,
                displayId,
                name,
                "C:\\business\\" + name,
                "application/pdf",
                42,
                sha256,
                AttachmentSource.SELECTED_FILE);
    }

    private static PreparedAttachment prepared(AttachmentMetadata metadata) {
        return new PreparedAttachment(
                metadata,
                Path.of(metadata.localPath()),
                new PreparedAttachment.FileIdentity(
                        metadata.sizeBytes(), FileTime.from(Instant.EPOCH), "file-key"));
    }

    private static List<AttachmentMetadata> attachments(int start, int count, long sizeBytes) {
        List<AttachmentMetadata> attachments = new ArrayList<>();
        for (int index = start; index < start + count; index++) {
            attachments.add(metadata(index, sizeBytes));
        }
        return attachments;
    }

    private static AttachmentMetadata metadata(int index, long sizeBytes) {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        return new AttachmentMetadata(
                new java.util.UUID(0, index + 1L).toString(),
                "A-23456" + alphabet.charAt(index),
                "file-" + index + ".pdf",
                "C:\\business\\file-" + index + ".pdf",
                "application/pdf",
                sizeBytes,
                Integer.toHexString(index).repeat(64).substring(0, 64),
                AttachmentSource.SELECTED_FILE);
    }

    private static String referenceText(List<AttachmentMetadata> attachments) {
        return String.join(
                " ",
                attachments.stream().map(AttachmentMetadata::displayId).toList());
    }

    private static void stubRevalidation(
            AttachmentFileValidator validator,
            List<AttachmentMetadata> attachments
    ) {
        org.mockito.Mockito.doAnswer(invocation -> {
            AttachmentRequest request = invocation.getArgument(0);
            return attachments.stream()
                    .filter(metadata -> metadata.id().equals(request.id()))
                    .findFirst()
                    .map(AttachmentHistoryResolverTest::prepared)
                    .orElseThrow();
        }).when(validator).validate(any());
    }

    private static void assertAmbiguous(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertCode(action, AttachmentErrorCode.ATTACHMENT_REFERENCE_AMBIGUOUS);
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            AttachmentErrorCode code
    ) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code()).isEqualTo(code));
    }
}
