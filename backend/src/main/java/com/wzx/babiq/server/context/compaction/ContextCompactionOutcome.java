package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.repository.ContextCompactionRecord;
import com.wzx.babiq.server.context.repository.ContextSummaryRecord;

/**
 * 自动压缩的执行结果。
 *
 * @param status 结果状态：NOT_NEEDED、SUCCESS、SKIPPED、FAILED
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

    /**
     * @return true 表示当前窗口已经安装了新的摘要
     */
    public boolean compacted() {
        return summaryRecord != null;
    }
}
