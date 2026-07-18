package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 线程级审批缓存。
 */
@Component
public final class PendingApprovals {

    /** threadId -> SAA HITL 暂停元数据；审批响应需要它恢复 Graph 执行。 */
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

    /** 仅当缓存仍是调用方预检到的同一份元数据时才原子消费。 */
    public InterruptionMetadata claim(String threadId, InterruptionMetadata expected) {
        if (threadId == null || expected == null) {
            return null;
        }
        AtomicReference<InterruptionMetadata> claimed = new AtomicReference<>();
        pendingByThread.computeIfPresent(threadId, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            claimed.set(current);
            return null;
        });
        return claimed.get();
    }

    /** 仅当缓存仍指向指定审批对象时删除，避免旧响应清理后续 replacement。 */
    public boolean removeExact(String threadId, InterruptionMetadata expected) {
        if (threadId == null || expected == null) {
            return false;
        }
        return pendingByThread.remove(threadId, expected);
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
