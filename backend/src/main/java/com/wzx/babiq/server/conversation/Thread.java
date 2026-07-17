package com.wzx.babiq.server.conversation;

import com.wzx.babiq.server.application.scope.BusinessIdentityScope;

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
 * @param businessIdentityScope 创建时冻结的业务身份；普通模式为 UNSCOPED
 */
public record Thread(
        String id,
        String cwd,
        Instant createdAt,
        BusinessIdentityScope businessIdentityScope
) {

    /** 兼容旧测试和 Task 28 之前恢复的无 scope 记录。 */
    public Thread(String id, String cwd, Instant createdAt) {
        this(id, cwd, createdAt, BusinessIdentityScope.UNSCOPED);
    }

    public Thread {
        businessIdentityScope = businessIdentityScope == null
                ? BusinessIdentityScope.UNSCOPED
                : businessIdentityScope;
    }

    /**
     * 创建一个新的业务线程记录。
     *
     * @param id 协议层线程标识
     * @param cwd 工作目录
     * @return 带当前创建时间的 Thread
     */
    public static Thread newThread(String id, String cwd) {
        return newThread(id, cwd, BusinessIdentityScope.UNSCOPED);
    }

    /** 使用请求边界已解析的身份创建 Thread，创建后不再读取当前登录态。 */
    public static Thread newThread(String id, String cwd, BusinessIdentityScope businessIdentityScope) {
        return new Thread(id, cwd, Instant.now(), businessIdentityScope);
    }
}
