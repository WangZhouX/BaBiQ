package com.wzx.babiq.server.observability;

import com.wzx.babiq.server.persistence.mapper.ToolCallMapper;
import com.wzx.babiq.server.persistence.mapper.TurnMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P2-5 本地可观测统计服务。
 *
 * <p>服务只读取 SQLite 持久化运行记录。P1 的 BaBiQMetrics 仍可作为实时短期计数器，
 * 但这里不读取它，避免后端重启后统计丢失。耗时、token、工具分布都通过 mapper 聚合 SQL 完成，
 * service 只负责把 Map 结果转换成稳定协议 DTO。</p>
 */
@Service
public class LocalObservabilityService {

    /** turn 聚合 mapper；负责 turn、summary、provider/model/status 维度 SQL。 */
    private final TurnMapper turnMapper;
    /** 工具聚合 mapper；负责 `bq_tool_calls` 维度 SQL。 */
    private final ToolCallMapper toolCallMapper;

    /**
     * 创建本地可观测统计服务。
     *
     * @param turnMapper turn 聚合 mapper
     * @param toolCallMapper 工具聚合 mapper
     */
    public LocalObservabilityService(TurnMapper turnMapper, ToolCallMapper toolCallMapper) {
        this.turnMapper = turnMapper;
        this.toolCallMapper = toolCallMapper;
    }

    /**
     * 生成完整本地统计快照。
     *
     * @param range 统计窗口，允许 7d、30d、all；空值由 handler 归一为 7d。
     * @param cwd 可选工作目录过滤；为空时统计所有工作目录。
     * @return 本地可观测快照
     */
    public LocalObservabilitySnapshot snapshot(String range, String cwd) {
        String normalizedRange = normalizeRange(range);
        String cutoff = cutoffInstant(normalizedRange);
        String normalizedCwd = blankToNull(cwd);
        return new LocalObservabilitySnapshot(
                normalizedRange,
                totals(cutoff, normalizedCwd),
                modelStats(turnMapper.selectObservabilityByProvider(cutoff, normalizedCwd)),
                modelStats(turnMapper.selectObservabilityByModel(cutoff, normalizedCwd)),
                toolStats(normalizedRange, normalizedCwd),
                statusStats(turnMapper.selectObservabilityByStatus(cutoff, normalizedCwd)));
    }

    /**
     * 查询工具调用聚合。
     *
     * @param range 统计窗口
     * @param cwd 可选工作目录过滤
     * @return 工具统计列表
     */
    public List<ToolStats> tools(String range, String cwd) {
        String normalizedRange = normalizeRange(range);
        return toolStats(normalizedRange, blankToNull(cwd));
    }

    /**
     * 查询 Provider/Model token 用量聚合。
     *
     * @param range 统计窗口
     * @param cwd 可选工作目录过滤
     * @return 模型用量统计列表
     */
    public List<ModelUsageStats> costs(String range, String cwd) {
        String normalizedRange = normalizeRange(range);
        return modelStats(turnMapper.selectObservabilityByModel(cutoffInstant(normalizedRange), blankToNull(cwd)));
    }

    private ObservabilityTotals totals(String cutoff, String cwd) {
        Map<String, Object> row = turnMapper.selectObservabilityTotals(cutoff, cwd);
        return new ObservabilityTotals(
                longValue(row, "turns"),
                longValue(row, "failedTurns"),
                longValue(row, "promptTokens"),
                longValue(row, "completionTokens"),
                longValue(row, "totalTokens"));
    }

    private List<ModelUsageStats> modelStats(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> new ModelUsageStats(
                        stringValue(row, "providerId"),
                        stringValue(row, "model"),
                        longValue(row, "turns"),
                        longValue(row, "failedTurns"),
                        longValue(row, "promptTokens"),
                        longValue(row, "completionTokens"),
                        longValue(row, "totalTokens")))
                .toList();
    }

    private List<ToolStats> toolStats(String normalizedRange, String cwd) {
        return toolCallMapper.selectObservabilityTools(cutoffInstant(normalizedRange), cwd).stream()
                .map(row -> new ToolStats(
                        stringValue(row, "toolName"),
                        longValue(row, "calls"),
                        longValue(row, "failures"),
                        longValue(row, "avgDurationMs")))
                .toList();
    }

    private List<StatusStats> statusStats(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> new StatusStats(stringValue(row, "status"), longValue(row, "turns")))
                .toList();
    }

    private String normalizeRange(String range) {
        String normalized = range == null || range.isBlank() ? "7d" : range.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("7d") && !normalized.equals("30d") && !normalized.equals("all")) {
            throw new IllegalArgumentException("非法统计窗口: " + range);
        }
        return normalized;
    }

    private String cutoffInstant(String range) {
        // all 表示不裁剪时间窗口；mapper 通过 cutoff IS NULL 分支跳过 started_at 条件。
        if (range.equals("all")) {
            return null;
        }
        Duration duration = range.equals("30d") ? Duration.ofDays(30) : Duration.ofDays(7);
        return Instant.now().minus(duration).toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String stringValue(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? null : value.toString();
    }

    private static long longValue(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    private static Object value(Map<String, Object> row, String key) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Object exact = row.get(key);
        if (exact != null || row.containsKey(key)) {
            return exact;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
