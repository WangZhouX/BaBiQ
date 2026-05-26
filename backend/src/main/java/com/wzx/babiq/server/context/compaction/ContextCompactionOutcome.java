package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.repository.ContextCompactionRecord;
import com.wzx.babiq.server.context.repository.ContextSummaryRecord;

/**
 * 自动压缩的执行结果。
 *
 * @param status 结果状态：NOT_NEEDED、SUCCESS、SKIPPED、FAILED、CONFLICT
 * @param summaryRecord 成功时生成的摘要记录
 * @param compactionRecord 本次压缩审计记录，未达到阈值时为空
 */
public record ContextCompactionOutcome(
        String status,
        ContextSummaryRecord summaryRecord,
        ContextCompactionRecord compactionRecord
) {

    /** 未达到阈值时不写审计记录。 */
    public static ContextCompactionOutcome notNeeded() {
        return new ContextCompactionOutcome("NOT_NEEDED", null, null);
    }

    /** 成功生成并保存摘要。 */
    public static ContextCompactionOutcome success(ContextSummaryRecord summaryRecord,
                                                   ContextCompactionRecord compactionRecord) {
        return new ContextCompactionOutcome("SUCCESS", summaryRecord, compactionRecord);
    }

    /** 达到阈值但没有合适来源可压缩。 */
    public static ContextCompactionOutcome skipped(ContextCompactionRecord compactionRecord) {
        return new ContextCompactionOutcome("SKIPPED", null, compactionRecord);
    }

    /** 达到阈值但压缩模型或持久化失败。 */
    public static ContextCompactionOutcome failed(ContextCompactionRecord compactionRecord) {
        return new ContextCompactionOutcome("FAILED", null, compactionRecord);
    }

    /** 成功生成摘要但安装窗口时发现 windowOrdinal 已被并发更新。 */
    public static ContextCompactionOutcome conflict(ContextCompactionRecord compactionRecord) {
        return new ContextCompactionOutcome("CONFLICT", null, compactionRecord);
    }

    /**
     * @return true 表示当前窗口已经安装了新的摘要；CONFLICT 下虽然生成过摘要正文，但不会暴露为已安装
     */
    public boolean compacted() {
        return "SUCCESS".equals(status) && summaryRecord != null;
    }
}
