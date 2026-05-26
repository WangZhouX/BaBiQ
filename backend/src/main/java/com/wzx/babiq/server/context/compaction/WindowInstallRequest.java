package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.repository.ContextWindowRecord;

/**
 * 压缩成功后安装 active window 的请求。
 *
 * <p>运行时会先根据当前窗口构造“下一版窗口”，再交给 ContextCompactionService 使用
 * compare-and-swap 写入。这样摘要、压缩审计和窗口安装可以在同一个事务边界内完成，
 * 如果并发 turn 已经更新 windowOrdinal，本轮会记录 CONFLICT 并继续使用未压缩上下文。</p>
 *
 * @param nextWindow 准备安装的新窗口，activeSummaryId 会由压缩服务替换为真实摘要 id
 * @param previousWindowOrdinal 期望匹配的旧窗口序号
 * @param inputSnapshotId 触发压缩时参考的快照 id，可为空
 * @param replacementSnapshotId 成功安装后窗口指向的新快照 id
 */
public record WindowInstallRequest(
        ContextWindowRecord nextWindow,
        int previousWindowOrdinal,
        String inputSnapshotId,
        String replacementSnapshotId
) {
}
