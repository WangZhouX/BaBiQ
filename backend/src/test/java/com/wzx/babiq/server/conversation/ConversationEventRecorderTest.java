package com.wzx.babiq.server.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.attachment.AttachmentMetadata;
import com.wzx.babiq.server.attachment.AttachmentReservationRegistry;
import com.wzx.babiq.server.attachment.AttachmentSource;
import com.wzx.babiq.server.attachment.PreparedAttachment;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.conversation.repository.TurnSummaryRecord;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * ConversationEventRecorder 测试。
 *
 * <p>P2-2 的关键规则是“先落库，再发 WebSocket 事件”。这个测试锁住 recorder 的落库语义，
 * ItemEmitter 只需要在发送前调用它即可。</p>
 */
class ConversationEventRecorderTest {

    /** 后端协议 JSON 序列化器，确保数据库 payload 与 WebSocket item 使用同一种字段名。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void record_item_added_should_save_raw_item_payload() {
        ConversationRepository repository = mock(ConversationRepository.class);
        ConversationEventRecorder recorder = new ConversationEventRecorder(
                repository,
                mock(TurnPersistenceService.class),
                objectMapper);

        recorder.recordItemAdded("thr_1", "turn_1", new UserMessageItem("it_user", "userMessage", "你好"));

        ArgumentCaptor<ItemRecord> captor = ArgumentCaptor.forClass(ItemRecord.class);
        verify(repository).saveItem(captor.capture());
        assertThat(captor.getValue().itemId()).isEqualTo("it_user");
        assertThat(captor.getValue().type()).isEqualTo("userMessage");
        assertThat(captor.getValue().payloadJson()).contains("\"text\":\"你好\"");
    }

    @Test
    void record_turn_summary_should_save_summary_table_and_item_payload() {
        ConversationRepository repository = mock(ConversationRepository.class);
        ConversationEventRecorder recorder = new ConversationEventRecorder(
                repository,
                mock(TurnPersistenceService.class),
                objectMapper);
        TurnSummaryItem item = new TurnSummaryItem(
                "it_summary",
                "turnSummary",
                "completed",
                "deepseek-v4-pro",
                10,
                20,
                30,
                2,
                1500);

        recorder.recordTurnSummary("thr_1", "turn_1", item);

        ArgumentCaptor<TurnSummaryRecord> captor = ArgumentCaptor.forClass(TurnSummaryRecord.class);
        verify(repository).saveTurnSummary(captor.capture());
        assertThat(captor.getValue().turnId()).isEqualTo("turn_1");
        assertThat(captor.getValue().totalTokens()).isEqualTo(30L);
        assertThat(captor.getValue().toolCount()).isEqualTo(2);
        verify(repository).saveItem(org.mockito.ArgumentMatchers.any(ItemRecord.class));
    }

    @Test
    void record_turn_finished_should_update_turn_status() {
        TurnPersistenceService turnPersistenceService = mock(TurnPersistenceService.class);
        ConversationEventRecorder recorder = new ConversationEventRecorder(
                mock(ConversationRepository.class),
                turnPersistenceService,
                objectMapper);

        recorder.recordTurnFinished("turn_1", "COMPLETED", null);

        verify(turnPersistenceService).updateTurnStatus("turn_1", "COMPLETED", null);
    }

    @Test
    void persisted_user_message_keeps_the_turn_attachment_reservation_until_executor_cleanup() {
        ConversationRepository repository = mock(ConversationRepository.class);
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry();
        PreparedAttachment attachment = attachment();
        AttachmentReservationRegistry.Reservation reservation = registry.reserve(
                "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment));
        reservation.bindToTurn("turn-a");
        ConversationEventRecorder recorder = new ConversationEventRecorder(
                repository,
                mock(TurnPersistenceService.class),
                null,
                objectMapper);

        recorder.recordItemAdded(
                "thread-a",
                "turn-a",
                UserMessageItem.of("it_user", "review", List.of(attachment.metadata())));

        assertThatThrownBy(() -> registry.reserve(
                "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment)))
                .isInstanceOf(com.wzx.babiq.server.attachment.AttachmentException.class);

        registry.releaseTurn("turn-a");
        try (AttachmentReservationRegistry.Reservation next = registry.reserve(
                "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment))) {
            assertThat(next.active()).isTrue();
        }
    }

    @Test
    void failed_user_message_persistence_keeps_the_reservation_until_executor_cleanup() {
        ConversationRepository repository = mock(ConversationRepository.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).saveItem(any(ItemRecord.class));
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry();
        PreparedAttachment attachment = attachment();
        AttachmentReservationRegistry.Reservation reservation = registry.reserve(
                "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment));
        reservation.bindToTurn("turn-a");
        ConversationEventRecorder recorder = new ConversationEventRecorder(
                repository,
                mock(TurnPersistenceService.class),
                null,
                objectMapper);

        assertThatThrownBy(() -> recorder.recordItemAdded(
                "thread-a",
                "turn-a",
                UserMessageItem.of("it_user", "review", List.of(attachment.metadata()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.reserve(
                "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment)))
                .isInstanceOf(com.wzx.babiq.server.attachment.AttachmentException.class);

        registry.releaseTurn("turn-a");
    }

    private static PreparedAttachment attachment() {
        AttachmentMetadata metadata = new AttachmentMetadata(
                "00000000-0000-0000-0000-000000000001",
                "A-234562",
                "contract.pdf",
                "C:\\business\\contract.pdf",
                "application/pdf",
                42,
                "a".repeat(64),
                AttachmentSource.SELECTED_FILE);
        return new PreparedAttachment(
                metadata,
                Path.of(metadata.localPath()),
                new PreparedAttachment.FileIdentity(
                        metadata.sizeBytes(), FileTime.from(Instant.EPOCH), "file-key"));
    }
}
