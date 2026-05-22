package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程级审批缓存。
 */
@Component
public final class PendingApprovals {

    private final Map<String, InterruptionMetadata> pendingByThread = new ConcurrentHashMap<>();

    /**
     * 保存一份待审批中断元数据。
     *
     * @param threadId 线程 id
     * @param metadata 中断元数据
     */
    public void put(String threadId, InterruptionMetadata metadata) {
        pendingByThread.put(threadId, metadata);
    }

    /**
     * 取出并删除待审批中断元数据。
     *
     * @param threadId 线程 id
     * @return 中断元数据
     */
    public InterruptionMetadata take(String threadId) {
        return pendingByThread.remove(threadId);
    }

    /**
     * 只读查看待审批元数据。
     *
     * @param threadId 线程 id
     * @return 中断元数据
     */
    public InterruptionMetadata peek(String threadId) {
        return pendingByThread.get(threadId);
    }

    /**
     * 删除指定线程的待审批元数据。
     *
     * @param threadId 线程 id
     */
    public void remove(String threadId) {
        pendingByThread.remove(threadId);
    }
}
