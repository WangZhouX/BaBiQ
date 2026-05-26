package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.repository.ContextCompactionRecord;
import com.wzx.babiq.server.context.repository.ContextSummaryRecord;

/**
 * 一次压缩尝试的内存态结果。
 *
 * <p>P3-3A 把“调用模型生成摘要”和“把摘要安装到 active window”拆开：前者可能成功生成候选摘要，
 * 后者仍可能因为 windowOrdinal 变化而失败。这个 record 只在服务内部传递候选摘要和审计草稿，
 * 真正落库由 ContextCompactionService 的安装阶段统一完成。</p>
 *
 * @param status 尝试状态，成功候选为 SUCCESS，失败或跳过会直接写入最终审计
 * @param summaryRecord 成功候选摘要，只有 SUCCESS 时存在
 * @param compactionRecord 压缩审计草稿，安装阶段可能会补充窗口安装字段
 */
record CompactionAttempt(
        String status,
        ContextSummaryRecord summaryRecord,
        ContextCompactionRecord compactionRecord
) {
}
