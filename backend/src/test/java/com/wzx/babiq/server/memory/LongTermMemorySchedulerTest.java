package com.wzx.babiq.server.memory;

import com.wzx.babiq.server.recovery.StartupRecoveryCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆后台调度器测试。
 *
 * <p>SQLite 只有一个写锁，启动恢复和长期记忆扫描都可能写库；这里确保恢复闸门打开前，
 * 定时任务不会抢在 `RecoveryStartupRunner` 前面写入数据库。</p>
 */
class LongTermMemorySchedulerTest {

    @Test
    @DisplayName("启动恢复完成前长期记忆定时扫描不会访问数据库流水线")
    void scheduled_scan_should_wait_until_startup_recovery_completes() {
        LongTermMemoryPipeline pipeline = mock(LongTermMemoryPipeline.class);
        MemoryStatusService statusService = mock(MemoryStatusService.class);
        StartupRecoveryCoordinator coordinator = new StartupRecoveryCoordinator();
        when(statusService.properties()).thenReturn(LongTermMemoryProperties.defaultsForTests());
        when(pipeline.runNextPhase1()).thenReturn(Optional.empty());
        LongTermMemoryScheduler scheduler = new LongTermMemoryScheduler(pipeline, statusService, coordinator);

        scheduler.scanPhase1Periodically();

        verify(pipeline, never()).scanPhase1();

        coordinator.markRecoveryComplete();
        scheduler.scanPhase1Periodically();

        verify(pipeline).scanPhase1();
    }

    @Test
    @DisplayName("启动恢复完成前长期记忆 Phase2 定时扫描也不会访问数据库流水线")
    void phase2_scheduled_scan_should_wait_until_startup_recovery_completes() {
        LongTermMemoryPipeline pipeline = mock(LongTermMemoryPipeline.class);
        MemoryStatusService statusService = mock(MemoryStatusService.class);
        StartupRecoveryCoordinator coordinator = new StartupRecoveryCoordinator();
        LongTermMemoryScheduler scheduler = new LongTermMemoryScheduler(pipeline, statusService, coordinator);

        scheduler.scanPhase2Periodically();

        verify(pipeline, never()).consolidate(false);

        coordinator.markRecoveryComplete();
        scheduler.scanPhase2Periodically();

        verify(pipeline).consolidate(false);
    }
}
