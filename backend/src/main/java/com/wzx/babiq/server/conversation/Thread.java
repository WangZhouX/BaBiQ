package com.wzx.babiq.server.conversation;

import java.time.Instant;

/**
 * BaBiQ 对话线程。
 *
 * <p>这里的 Thread 是业务概念,不是 Java 执行线程。它用于承载同一工作目录下
 * 的多轮 Turn,后续 P1-2 的模型记忆和 P2+ 的桌面会话都会通过 threadId 关联
 * 上下文。</p>
 *
 * @param id 协议层线程标识,固定以 thr_ 开头
 * @param cwd 用户启动对话时的工作目录
 * @param createdAt 线程创建时间
 */
public record Thread(
        String id,
        String cwd,
        Instant createdAt
) {

    /**
     * 创建一个新的业务线程记录。
     *
     * @param id 协议层线程标识
     * @param cwd 工作目录
     * @return 带当前创建时间的 Thread
     */
    public static Thread newThread(String id, String cwd) {
        return new Thread(id, cwd, Instant.now());
    }
}
