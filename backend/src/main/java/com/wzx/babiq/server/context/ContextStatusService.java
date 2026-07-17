package com.wzx.babiq.server.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.dto.ContextSnapshotDto;
import com.wzx.babiq.server.api.dto.ContextSnapshotItemDto;
import com.wzx.babiq.server.api.dto.ContextStatusResult;
import com.wzx.babiq.server.context.repository.ContextSnapshotRecord;
import com.wzx.babiq.server.context.repository.ContextSnapshotRepository;
import com.wzx.babiq.server.context.repository.ContextWindowRecord;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import com.wzx.babiq.server.context.repository.ContextCompactionRepository;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 上下文窗口查询服务。
 *
 * <p>该服务面向 JSON-RPC 和运行详情面板聚合窗口状态，不参与 prompt 拼装，避免查询链路反向污染 Agent 输入。</p>
 */
@Service
public class ContextStatusService {

    /** thread 级窗口仓库，读取当前窗口状态。 */
    private final ContextWindowRepository windowRepository;
    /** 快照仓库，读取最近快照和快照详情。 */
    private final ContextSnapshotRepository snapshotRepository;
    /** 压缩审计仓库，读取压缩次数和最近状态。 */
    private final ContextCompactionRepository compactionRepository;
    /** JSON mapper，用于把 items_json 转成桌面端可读 DTO。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建上下文窗口查询服务。
     *
     * @param windowRepository 窗口仓库
     * @param snapshotRepository 快照仓库
     * @param objectMapper JSON mapper
     */
    public ContextStatusService(ContextWindowRepository windowRepository,
                                ContextSnapshotRepository snapshotRepository,
                                ObjectMapper objectMapper) {
        this(windowRepository, snapshotRepository, null, objectMapper);
    }

    /**
     * 创建上下文窗口查询服务。
     *
     * @param windowRepository 窗口仓库
     * @param snapshotRepository 快照仓库
     * @param compactionRepository 压缩审计仓库
     * @param objectMapper JSON mapper
     */
    @Autowired
    public ContextStatusService(ContextWindowRepository windowRepository,
                                ContextSnapshotRepository snapshotRepository,
                                ContextCompactionRepository compactionRepository,
                                ObjectMapper objectMapper) {
        this.windowRepository = windowRepository;
        this.snapshotRepository = snapshotRepository;
        this.compactionRepository = compactionRepository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /**
     * 查询某个 thread 当前上下文窗口状态。
     *
     * @param threadId 会话 id
     * @return 窗口状态；尚未生成快照时返回 empty 状态
     */
    public ContextStatusResult status(String threadId) {
        Optional<ContextWindowRecord> window = windowRepository.findByThreadId(threadId);
        if (window.isEmpty()) {
            return new ContextStatusResult(threadId, 0, 0, 0, null, 0, null, 0.0d,
                    "empty", null, 0, null);
        }
        ContextWindowRecord record = window.get();
        Optional<ContextSnapshotRecord> snapshot = Optional.empty();
        if (record.lastSnapshotId() != null && !record.lastSnapshotId().isBlank()) {
            snapshot = snapshotRepository.findBySnapshotId(record.lastSnapshotId());
        }
        if (snapshot.isEmpty()) {
            snapshot = snapshotRepository.findLatestByThreadId(threadId);
        }
        int estimated = snapshot.map(ContextSnapshotRecord::estimatedTokens).orElse(0);
        Long actual = snapshot.map(ContextSnapshotRecord::actualPromptTokens).orElse(null);
        double ratio = usageRatio(record.modelContextWindow(), actual == null ? estimated : actual);
        String status = record.autoCompactThreshold() > 0
                && (actual == null ? estimated : actual) >= record.autoCompactThreshold()
                ? "over_threshold"
                : "ok";
        return new ContextStatusResult(
                record.threadId(),
                record.windowOrdinal(),
                record.modelContextWindow(),
                record.autoCompactThreshold(),
                snapshot.map(ContextSnapshotRecord::snapshotId).orElse(record.lastSnapshotId()),
                estimated,
                actual,
                ratio,
                status,
                record.activeSummaryId(),
                compactionCount(threadId),
                lastCompactionStatus(threadId));
    }

    public ContextStatusResult status(String threadId, BusinessIdentityScope scope) {
        Optional<ContextWindowRecord> window = windowRepository.findByThreadId(threadId, scope);
        if (window.isEmpty()) {
            throw new IllegalArgumentException("context not found");
        }
        ContextWindowRecord record = window.get();
        Optional<ContextSnapshotRecord> snapshot = Optional.empty();
        if (record.lastSnapshotId() != null && !record.lastSnapshotId().isBlank()) {
            snapshot = snapshotRepository.findBySnapshotId(record.lastSnapshotId(), scope);
        }
        if (snapshot.isEmpty()) {
            snapshot = snapshotRepository.findLatestByThreadId(threadId, scope);
        }
        return statusResult(record, snapshot, false);
    }

    private long compactionCount(String threadId) {
        return compactionRepository == null ? 0 : compactionRepository.countByThreadId(threadId);
    }

    private String lastCompactionStatus(String threadId) {
        if (compactionRepository == null) {
            return null;
        }
        return compactionRepository.findLatestByThreadId(threadId)
                .map(com.wzx.babiq.server.context.repository.ContextCompactionRecord::status)
                .orElse(null);
    }

    /**
     * 查询单个上下文快照详情。
     *
     * @param snapshotId 快照 id
     * @return 快照 DTO；不存在时为空
     */
    public Optional<ContextSnapshotDto> snapshot(String snapshotId) {
        return snapshotRepository.findBySnapshotId(snapshotId).map(this::toDto);
    }

    public Optional<ContextSnapshotDto> snapshot(String snapshotId, BusinessIdentityScope scope) {
        return snapshotRepository.findBySnapshotId(snapshotId, scope).map(this::toDto);
    }

    /**
     * 查询某个 turn 最近上下文快照详情，运行记录面板使用。
     *
     * @param turnId turn id
     * @return 快照 DTO；不存在时为空
     */
    public Optional<ContextSnapshotDto> latestForTurn(String turnId) {
        return snapshotRepository.findLatestByTurnId(turnId).map(this::toDto);
    }

    public Optional<ContextSnapshotDto> latestForTurn(String turnId, BusinessIdentityScope scope) {
        return snapshotRepository.findLatestByTurnId(turnId, scope).map(this::toDto);
    }

    private ContextSnapshotDto toDto(ContextSnapshotRecord record) {
        long tokenForRatio = record.actualPromptTokens() == null ? record.estimatedTokens() : record.actualPromptTokens();
        return new ContextSnapshotDto(
                record.snapshotId(),
                record.threadId(),
                record.turnId(),
                record.phase(),
                record.providerId(),
                record.model(),
                record.cwd(),
                record.windowOrdinal(),
                record.modelContextWindow(),
                record.autoCompactThreshold(),
                record.estimatedTokens(),
                record.actualPromptTokens(),
                record.includedItemCount(),
                record.excludedItemCount(),
                usageRatio(record.modelContextWindow(), tokenForRatio),
                record.inputPreview(),
                record.createdAt().toString(),
                parseItems(record.itemsJson()));
    }

    private List<ContextSnapshotItemDto> parseItems(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(itemsJson);
            if (!root.isArray()) {
                return List.of();
            }
            List<ContextSnapshotItemDto> items = new ArrayList<>();
            for (JsonNode node : root) {
                items.add(new ContextSnapshotItemDto(
                        readText(node, "sourceId", "source_id"),
                        readText(node, "sourceType", "source_type"),
                        readText(node, "priority"),
                        node.path("included").asBoolean(false),
                        readText(node, "reason"),
                        readInt(node, "tokenEstimate", "token_estimate")));
            }
            return List.copyOf(items);
        } catch (Exception exception) {
            throw new IllegalStateException("上下文快照 items_json 无法解析", exception);
        }
    }

    private static String readText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private static int readInt(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.canConvertToInt()) {
                return value.asInt();
            }
        }
        return 0;
    }

    private static double usageRatio(int modelContextWindow, long tokens) {
        if (modelContextWindow <= 0) {
            return 0.0d;
        }
        return Math.min(1.0d, Math.max(0.0d, tokens / (double) modelContextWindow));
    }

    private ContextStatusResult statusResult(
            ContextWindowRecord record, Optional<ContextSnapshotRecord> snapshot) {
        return statusResult(record, snapshot, true);
    }

    private ContextStatusResult statusResult(
            ContextWindowRecord record, Optional<ContextSnapshotRecord> snapshot, boolean includeCompaction) {
        int estimated = snapshot.map(ContextSnapshotRecord::estimatedTokens).orElse(0);
        Long actual = snapshot.map(ContextSnapshotRecord::actualPromptTokens).orElse(null);
        double ratio = usageRatio(record.modelContextWindow(), actual == null ? estimated : actual);
        String status = record.autoCompactThreshold() > 0
                && (actual == null ? estimated : actual) >= record.autoCompactThreshold()
                ? "over_threshold" : "ok";
        return new ContextStatusResult(
                record.threadId(), record.windowOrdinal(), record.modelContextWindow(),
                record.autoCompactThreshold(),
                snapshot.map(ContextSnapshotRecord::snapshotId).orElse(record.lastSnapshotId()),
                estimated, actual, ratio, status, record.activeSummaryId(),
                includeCompaction ? compactionCount(record.threadId()) : 0,
                includeCompaction ? lastCompactionStatus(record.threadId()) : null);
    }
}
