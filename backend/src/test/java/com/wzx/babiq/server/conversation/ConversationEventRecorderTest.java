package com.wzx.babiq.server.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.conversation.repository.TurnSummaryRecord;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
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
                new BigDecimal("0.0003"),
                1500);

        recorder.recordTurnSummary("thr_1", "turn_1", item);

        ArgumentCaptor<TurnSummaryRecord> captor = ArgumentCaptor.forClass(TurnSummaryRecord.class);
        verify(repository).saveTurnSummary(captor.capture());
        assertThat(captor.getValue().turnId()).isEqualTo("turn_1");
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
}
