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

    private static void assertAmbiguous(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(AttachmentErrorCode.ATTACHMENT_REFERENCE_AMBIGUOUS));
    }
}
