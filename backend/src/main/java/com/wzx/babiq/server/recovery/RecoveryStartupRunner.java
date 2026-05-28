package com.wzx.babiq.server.recovery;

import com.wzx.babiq.server.context.compaction.ContextCompactionRecoveryService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spring Boot 启动后的恢复入口。
 *
 * <p>官方推荐用 ApplicationRunner 执行“应用启动完成后的一次性逻辑”。这里不放业务算法，
 * 只调用 TurnRecoveryService，保证启动生命周期和恢复语义可以分别测试。</p>
 */
@Component
public class RecoveryStartupRunner implements ApplicationRunner {

    /** 真正执行恢复语义的服务。 */
    private final TurnRecoveryService recoveryService;
    /** P3-3A 短期压缩恢复服务；使用 ObjectProvider 避免未来裁剪上下文模块时影响启动。 */
    private final ObjectProvider<ContextCompactionRecoveryService> compactionRecoveryService;
    /** 启动恢复闸门，恢复完成后才允许长期记忆等后台调度器写库。 */
    private final StartupRecoveryCoordinator startupRecoveryCoordinator;

    /**
     * 创建启动恢复 runner。
     *
     * @param recoveryService turn 恢复服务
     * @param compactionRecoveryService 短期压缩恢复服务提供器
     * @param startupRecoveryCoordinator 启动恢复闸门，通知后台调度器恢复已经完成
     */
    public RecoveryStartupRunner(TurnRecoveryService recoveryService,
                                 ObjectProvider<ContextCompactionRecoveryService> compactionRecoveryService,
                                 StartupRecoveryCoordinator startupRecoveryCoordinator) {
        this.recoveryService = recoveryService;
        this.compactionRecoveryService = compactionRecoveryService;
        this.startupRecoveryCoordinator = startupRecoveryCoordinator;
    }

    /**
     * Spring Boot 启动完成时自动调用。
     *
     * @param args 启动参数；当前恢复逻辑不需要读取
     */
    @Override
    public void run(ApplicationArguments args) {
        recoveryService.recoverAbandonedState();
        compactionRecoveryService.ifAvailable(ContextCompactionRecoveryService::scan);
        startupRecoveryCoordinator.markRecoveryComplete();
    }
}
