package com.wzx.babiq.server.context.compaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.wzx.babiq.server.api.dto.ContextCompactResult;
import com.wzx.babiq.server.context.model.ShortTermSummary;
import com.wzx.babiq.server.context.repository.ContextSummaryRecord;
import com.wzx.babiq.server.context.repository.ContextSummaryRepository;
import com.wzx.babiq.server.context.repository.ContextWindowRecord;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.persistence.entity.ThreadEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 用户手动触发的上下文压缩服务。
 *
 * <p>手动压缩复用 ContextCompactionService 的来源选择、摘要生成和审计写入，只额外负责把成功摘要安装到
 * bq_context_windows。它不创建普通聊天消息，因此不会污染对话历史。</p>
 */
@Service
public class ContextManualCompactionService {

    private static final int HISTORY_LIMIT = 200;

    /** 对话仓库，用于读取 thread 元数据和历史 item。 */
    private final ConversationRepository conversationRepository;
    /** 当前窗口仓库，成功后安装 active summary。 */
    private final ContextWindowRepository windowRepository;
    /** 摘要仓库，用于恢复当前 active summary 边界。 */
    private final ContextSummaryRepository summaryRepository;
    /** 自动压缩核心服务。 */
    private final ContextCompactionService compactionService;
    /** ThreadItem payload JSON 解析器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建手动压缩服务。
     */
    public ContextManualCompactionService(ConversationRepository conversationRepository,
                                          ContextWindowRepository windowRepository,
                                          ContextSummaryRepository summaryRepository,
                                          ContextCompactionService compactionService,
                                          ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.windowRepository = windowRepository;
        this.summaryRepository = summaryRepository;
        this.compactionService = compactionService;
        ObjectMapper base = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.objectMapper = base.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    /**
     * 强制压缩指定 thread 的旧历史。
     *
     * @param threadId 会话 id
     * @return 压缩结果
     */
    public ContextCompactResult compact(String threadId) {
        ThreadEntity thread = conversationRepository.findThread(threadId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + threadId));
        ContextWindowRecord existingWindow = windowRepository.findByThreadId(threadId).orElse(null);
        ShortTermSummary activeSummary = activeSummary(existingWindow).orElse(null);
        int currentOrdinal = existingWindow == null ? 0 : existingWindow.windowOrdinal();
        int modelWindow = existingWindow != null && existingWindow.modelContextWindow() > 0
                ? existingWindow.modelContextWindow()
                : 32_768;
        List<ItemRecord> itemRecords = conversationRepository.listItems(threadId, HISTORY_LIMIT);
        if (itemRecords.isEmpty()) {
            return new ContextCompactResult(threadId, "SKIPPED", null, null, currentOrdinal);
        }
        String sourceTurnId = itemRecords.getLast().turnId();
        ContextCompactionOutcome outcome = compactionService.compactIfNeeded(new ContextCompactionRequest(
                threadId,
                sourceTurnId,
                thread.getProviderId(),
                thread.getModel(),
                historyItems(itemRecords),
                activeSummary,
                Integer.MAX_VALUE,
                modelWindow,
                "用户手动触发上下文压缩",
                true));
        int nextOrdinal = outcome.compacted() ? currentOrdinal + 1 : currentOrdinal;
        if (outcome.compacted()) {
            Instant now = Instant.now();
            windowRepository.upsert(new ContextWindowRecord(
                    threadId,
                    nextOrdinal,
                    outcome.summaryRecord().summaryId(),
                    modelWindow,
                    compactionService.budgetFor(modelWindow).autoCompactThresholdTokens(),
                    existingWindow == null ? null : existingWindow.lastSnapshotId(),
                    existingWindow == null ? now : existingWindow.createdAt(),
                    now));
        }
        return new ContextCompactResult(
                threadId,
                outcome.status(),
                outcome.summaryRecord() == null ? null : outcome.summaryRecord().summaryId(),
                outcome.compactionRecord() == null ? null : outcome.compactionRecord().compactionId(),
                nextOrdinal);
    }

    private List<ThreadItem> historyItems(List<ItemRecord> itemRecords) {
        return itemRecords.stream()
                .flatMap(record -> parseThreadItem(record).stream())
                .toList();
    }

    private Optional<ThreadItem> parseThreadItem(ItemRecord record) {
        try {
            return Optional.of(objectMapper.readValue(record.payloadJson(), ThreadItem.class));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<ShortTermSummary> activeSummary(ContextWindowRecord existingWindow) {
        if (existingWindow == null || existingWindow.activeSummaryId() == null) {
            return Optional.empty();
        }
        return summaryRepository.findBySummaryId(existingWindow.activeSummaryId())
                .map(this::toShortTermSummary);
    }

    private ShortTermSummary toShortTermSummary(ContextSummaryRecord record) {
        return new ShortTermSummary(
                record.summaryId(),
                record.sourceItemRange(),
                record.summary(),
                record.sourceStartItemId(),
                record.sourceEndItemId());
    }
}
