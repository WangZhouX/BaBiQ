package com.wzx.babiq.server.recovery;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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

    /**
     * 创建启动恢复 runner。
     *
     * @param recoveryService turn 恢复服务
     */
    public RecoveryStartupRunner(TurnRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    /**
     * Spring Boot 启动完成时自动调用。
     *
     * @param args 启动参数；当前恢复逻辑不需要读取
     */
    @Override
    public void run(ApplicationArguments args) {
        recoveryService.recoverAbandonedState();
    }
}
