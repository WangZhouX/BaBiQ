package com.wzx.babiq.server.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 长期记忆后台调度器。
 *
 * <p>调度器延续 Codex 的“启动/周期扫描 + idle 保护”思路：扫描负责创建可审计 job，
 * worker 负责按批次领取 job。这样既能自动推进长期记忆，也不会在每个 turn 结束后立刻产生模型调用热回路。</p>
 */
@Component
public class LongTermMemoryScheduler {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryScheduler.class);

    /** 长期记忆流水线。 */
    private final LongTermMemoryPipeline pipeline;
    /** 运行时设置服务，用于读取用户是否暂停生成以及每批处理上限。 */
    private final MemoryStatusService statusService;

    public LongTermMemoryScheduler(LongTermMemoryPipeline pipeline, MemoryStatusService statusService) {
        this.pipeline = pipeline;
        this.statusService = statusService;
    }

    /**
     * 应用启动后执行一次 Phase1 idle 扫描。
     *
     * <p>启动扫描只排队，不立即调用模型；真正抽取由周期 worker 接管，避免应用启动时抢占太多资源。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        LongTermMemoryProperties properties = statusService.properties();
        if (!properties.phase1OnStartup()) {
            return;
        }
        try {
            int queued = pipeline.scanPhase1();
            log.info("长期记忆启动扫描完成: queuedPhase1={}", queued);
        } catch (RuntimeException exception) {
            log.warn("长期记忆启动扫描失败: reason={}: {}", exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    /**
     * 周期扫描 idle thread，并批量处理少量 Phase1 job。
     */
    @Scheduled(fixedDelayString = "${babiq.memory.long-term.phase1-scan-interval-millis:3600000}")
    public void scanPhase1Periodically() {
        try {
            int queued = pipeline.scanPhase1();
            int processed = runQueuedPhase1Batch();
            log.info("长期记忆 Phase1 周期扫描完成: queued={}, processed={}", queued, processed);
        } catch (RuntimeException exception) {
            log.warn("长期记忆 Phase1 周期扫描失败: reason={}: {}", exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    /**
     * 周期兜底检查 Phase2 是否需要入队，并尝试处理一个归并 job。
     */
    @Scheduled(fixedDelayString = "${babiq.memory.long-term.phase2-scan-interval-millis:86400000}")
    public void scanPhase2Periodically() {
        try {
            pipeline.consolidate(false);
            pipeline.runNextPhase2();
        } catch (RuntimeException exception) {
            log.warn("长期记忆 Phase2 周期扫描失败: reason={}: {}", exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    private int runQueuedPhase1Batch() {
        int processed = 0;
        int max = statusService.properties().phase1MaxThreadsPerScan();
        for (int index = 0; index < max; index++) {
            if (pipeline.runNextPhase1().isEmpty()) {
                break;
            }
            processed++;
        }
        return processed;
    }
}
