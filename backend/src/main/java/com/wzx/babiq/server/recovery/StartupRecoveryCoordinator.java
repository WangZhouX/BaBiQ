package com.wzx.babiq.server.recovery;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动恢复协调器。
 *
 * <p>BaBiQ 启动时会先修复上一次异常退出遗留的 turn、approval 和压缩状态；
 * 长期记忆等后台调度任务也会写 SQLite。SQLite 同一时间只能有一个写事务，
 * 因此需要一个很小的进程内闸门，确保恢复流程完成前后台任务不抢先写库。</p>
 */
@Component
public class StartupRecoveryCoordinator {

    /**
     * 启动恢复是否已经结束。
     *
     * <p>false 表示 ApplicationRunner 仍可能在修复遗留运行状态，后台调度器应该跳过本次扫描；
     * true 表示恢复已经收口，周期任务可以按自己的节奏写入数据库。</p>
     */
    private final AtomicBoolean recoveryComplete = new AtomicBoolean(false);

    /**
     * 判断后台任务是否可以开始访问需要写事务的持久化流水线。
     *
     * @return true 表示启动恢复完成，false 表示调度任务应跳过本轮
     */
    public boolean isRecoveryComplete() {
        return recoveryComplete.get();
    }

    /**
     * 标记启动恢复已经完成。
     *
     * <p>该方法由 `RecoveryStartupRunner` 在 turn 恢复和压缩恢复都处理完成后调用。</p>
     */
    public void markRecoveryComplete() {
        recoveryComplete.set(true);
    }
}
