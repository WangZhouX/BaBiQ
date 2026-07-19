package com.wzx.babiq.server.settings;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Provider 机器级变更协调器。
 *
 * <p>Provider CRUD、启动恢复和 active 设置共享同一把专责锁；registry 自身 monitor
 * 只由单个短方法持有，ChatClient 缓存失效时绝不持有 registry monitor。</p>
 */
@Component
public final class ProviderMutationCoordinator {

    private final ReentrantLock mutationLock = new ReentrantLock();

    /** 串行执行有返回值的 Provider 机器级变更。 */
    public <T> T execute(Supplier<T> mutation) {
        mutationLock.lock();
        try {
            return mutation.get();
        } finally {
            mutationLock.unlock();
        }
    }

    /** 串行执行无返回值的 Provider 机器级变更。 */
    public void execute(Runnable mutation) {
        mutationLock.lock();
        try {
            mutation.run();
        } finally {
            mutationLock.unlock();
        }
    }
}
