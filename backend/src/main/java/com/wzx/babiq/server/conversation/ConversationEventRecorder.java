package com.wzx.babiq.server.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.conversation.repository.TurnSummaryRecord;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 运行事件持久化记录器。
 *
 * <p>ItemEmitter 负责 WebSocket 输出，ConversationEventRecorder 负责把同一份协议 item 写入 SQLite。
 * P2-2 采用“先落库，再发事件”的顺序：如果数据库写失败，前端不会看到无法恢复的幽灵消息。</p>
 */
@Component
public class ConversationEventRecorder {

    /** 对话仓库，负责保存 item 和 turnSummary。 */
    private final ConversationRepository repository;
    /** turn 表服务，负责更新 turn 的最终状态。 */
    private final TurnPersistenceService turnPersistenceService;
    /** 和 WebSocket 协议共用的 JSON 序列化器，保证 payload_json 字段保持协议原文。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建运行事件记录器。
     *
     * @param repository 对话持久化仓库
     * @param turnPersistenceService turn 表持久化服务
     * @param objectMapper JSON 序列化器
     */
    public ConversationEventRecorder(
            ConversationRepository repository,
            TurnPersistenceService turnPersistenceService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.turnPersistenceService = turnPersistenceService;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录新增 item。
     *
     * @param threadId item 所属会话
     * @param turnId item 所属运行回合
     * @param item 后端即将发送给桌面端的协议 item
     */
    public void recordItemAdded(String threadId, String turnId, ThreadItem item) {
        saveItem(threadId, turnId, item, statusOf(item, "completed"));
    }

    /**
     * 记录更新 item。
     *
     * @param threadId item 所属会话
     * @param turnId item 所属运行回合
     * @param item 更新后的完整协议 item
     */
    public void recordItemUpdated(String threadId, String turnId, ThreadItem item) {
        saveItem(threadId, turnId, item, statusOf(item, "updated"));
    }

    /**
     * 记录完成 item。
     *
     * @param threadId item 所属会话
     * @param turnId item 所属运行回合
     * @param item 完成态协议 item
     */
    public void recordItemCompleted(String threadId, String turnId, ThreadItem item) {
        saveItem(threadId, turnId, item, statusOf(item, "completed"));
    }

    /**
     * 记录 turnSummary，同时写入 bq_items 和 bq_turn_summaries。
     *
     * @param threadId 摘要所属会话
     * @param turnId 摘要所属 turn
     * @param item turnSummary 协议 item
     */
    public void recordTurnSummary(String threadId, String turnId, TurnSummaryItem item) {
        saveItem(threadId, turnId, item, item.status());
        repository.saveTurnSummary(TurnSummaryRecord.of(
                turnId,
                item.promptTokens(),
                item.completionTokens(),
                item.estimatedCostUsd() == null ? BigDecimal.ZERO : item.estimatedCostUsd(),
                item.durationMs(),
                item.toolCalls(),
                Instant.now()));
    }

    /**
     * 记录 turn 终态。
     *
     * @param turnId turn id
     * @param status 数据库中保存的终态，例如 COMPLETED、FAILED、CANCELED
     * @param failureReason 失败原因；非失败状态为空
     */
    public void recordTurnFinished(String turnId, String status, String failureReason) {
        turnPersistenceService.updateTurnStatus(turnId, status, failureReason);
    }

    private void saveItem(String threadId, String turnId, ThreadItem item, String status) {
        Instant now = Instant.now();
        repository.saveItem(ItemRecord.of(
                item.id(),
                threadId,
                turnId,
                item.type(),
                0,
                toPayloadJson(item),
                status,
                now));
    }

    private String toPayloadJson(ThreadItem item) {
        try {
            return objectMapper.writeValueAsString(item);
        } catch (Exception exception) {
            throw new IllegalStateException("协议 item 无法序列化: " + item.id(), exception);
        }
    }

    private String statusOf(ThreadItem item, String fallback) {
        JsonNode payload = objectMapper.valueToTree(item);
        return payload.path("status").asText(fallback);
    }
}
