package com.wzx.babiq.server.api.dto;

/**
 * context/compact 的返回结果。
 *
 * @param threadId 会话 id。
 * @param status 压缩状态：SUCCESS、SKIPPED、FAILED。
 * @param summaryId 成功时安装的摘要 id。
 * @param compactionId 本次压缩审计 id。
 * @param windowOrdinal 当前窗口序号。
 */
public record ContextCompactResult(
        String threadId,
        String status,
        String summaryId,
        String compactionId,
        int windowOrdinal
) {
}
