package com.wzx.babiq.server.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 长期记忆流水线配置。
 *
 * <p>默认值参考 Codex 的“启动/周期扫描 + idle 保护”思路，而不是每轮 turn 结束立即调用模型。
 * 这样能避免用户连续对话时产生热回路和不必要成本。</p>
 *
 * @param enabled 长期记忆总开关
 * @param generateEnabled 是否允许后台 Phase1/Phase2 生成候选和产物
 * @param readEnabled 是否允许 read path 把 memory_summary 注入上下文窗口
 * @param rootDir Markdown 镜像根目录
 * @param phase1ScanIntervalMillis Phase1 周期扫描间隔
 * @param phase1MinIdleMillis thread 最小空闲时间
 * @param phase1MaxThreadsPerScan 每轮 Phase1 最多处理线程数
 * @param phase1OnStartup 是否启动后扫描
 * @param phase1InputWindowPercent Phase1 输入占模型窗口百分比
 * @param phase1FallbackTokenLimit 无模型窗口时的 Phase1 输入上限
 * @param phase2TriggerOnCandidateCount CLEAN 候选达到多少触发 Phase2
 * @param phase2ScanIntervalMillis Phase2 兜底扫描间隔
 * @param phase2MinIntervalMillis 自动 Phase2 最小间隔
 * @param phase2MaxCandidates 每次 Phase2 最多选择候选数
 * @param readBudgetTokens read path 注入 memory_summary 的 token 预算
 */
@ConfigurationProperties(prefix = "babiq.memory.long-term")
public record LongTermMemoryProperties(
        boolean enabled,
        boolean generateEnabled,
        boolean readEnabled,
        Path rootDir,
        long phase1ScanIntervalMillis,
        long phase1MinIdleMillis,
        int phase1MaxThreadsPerScan,
        boolean phase1OnStartup,
        int phase1InputWindowPercent,
        int phase1FallbackTokenLimit,
        int phase2TriggerOnCandidateCount,
        long phase2ScanIntervalMillis,
        long phase2MinIntervalMillis,
        int phase2MaxCandidates,
        int readBudgetTokens
) {

    /**
     * Spring 配置绑定后的默认值兜底。
     */
    public LongTermMemoryProperties {
        if (rootDir == null) {
            rootDir = Path.of(System.getProperty("user.home"), ".babiq", "memories");
        }
        phase1ScanIntervalMillis = phase1ScanIntervalMillis <= 0 ? 3_600_000 : phase1ScanIntervalMillis;
        phase1MinIdleMillis = phase1MinIdleMillis <= 0 ? 300_000 : phase1MinIdleMillis;
        phase1MaxThreadsPerScan = phase1MaxThreadsPerScan <= 0 ? 4 : phase1MaxThreadsPerScan;
        phase1InputWindowPercent = phase1InputWindowPercent <= 0 ? 70 : phase1InputWindowPercent;
        phase1FallbackTokenLimit = phase1FallbackTokenLimit <= 0 ? 150_000 : phase1FallbackTokenLimit;
        phase2TriggerOnCandidateCount = phase2TriggerOnCandidateCount <= 0 ? 5 : phase2TriggerOnCandidateCount;
        phase2ScanIntervalMillis = phase2ScanIntervalMillis <= 0 ? 86_400_000 : phase2ScanIntervalMillis;
        phase2MinIntervalMillis = phase2MinIntervalMillis <= 0 ? 3_600_000 : phase2MinIntervalMillis;
        phase2MaxCandidates = phase2MaxCandidates <= 0 ? 256 : phase2MaxCandidates;
        readBudgetTokens = readBudgetTokens <= 0 ? 2_500 : readBudgetTokens;
    }

    /**
     * 单元测试使用的默认配置。
     */
    public static LongTermMemoryProperties defaultsForTests() {
        return new LongTermMemoryProperties(true, true, true, Path.of("target", "test-memories"),
                3_600_000, 300_000, 4, true, 70, 150_000,
                5, 86_400_000, 3_600_000, 256, 2_500);
    }

    /**
     * 返回只修改 read budget 的副本。
     */
    public LongTermMemoryProperties withReadBudgetTokens(int readBudgetTokens) {
        return new LongTermMemoryProperties(enabled, generateEnabled, readEnabled, rootDir,
                phase1ScanIntervalMillis, phase1MinIdleMillis, phase1MaxThreadsPerScan, phase1OnStartup,
                phase1InputWindowPercent, phase1FallbackTokenLimit, phase2TriggerOnCandidateCount,
                phase2ScanIntervalMillis, phase2MinIntervalMillis, phase2MaxCandidates, readBudgetTokens);
    }

    /**
     * 返回只修改 Phase2 最小间隔的副本。
     */
    public LongTermMemoryProperties withPhase2MinIntervalMillis(long phase2MinIntervalMillis) {
        return new LongTermMemoryProperties(enabled, generateEnabled, readEnabled, rootDir,
                phase1ScanIntervalMillis, phase1MinIdleMillis, phase1MaxThreadsPerScan, phase1OnStartup,
                phase1InputWindowPercent, phase1FallbackTokenLimit, phase2TriggerOnCandidateCount,
                phase2ScanIntervalMillis, phase2MinIntervalMillis, phase2MaxCandidates, readBudgetTokens);
    }

    /**
     * 返回只修改 Phase2 自动触发阈值的副本。
     */
    public LongTermMemoryProperties withPhase2TriggerOnCandidateCount(int phase2TriggerOnCandidateCount) {
        return new LongTermMemoryProperties(enabled, generateEnabled, readEnabled, rootDir,
                phase1ScanIntervalMillis, phase1MinIdleMillis, phase1MaxThreadsPerScan, phase1OnStartup,
                phase1InputWindowPercent, phase1FallbackTokenLimit, phase2TriggerOnCandidateCount,
                phase2ScanIntervalMillis, phase2MinIntervalMillis, phase2MaxCandidates, readBudgetTokens);
    }

    /**
     * 返回运行时开关更新后的副本。
     */
    public LongTermMemoryProperties withSwitches(Boolean enabled, Boolean generateEnabled, Boolean readEnabled) {
        return new LongTermMemoryProperties(
                enabled == null ? this.enabled : enabled,
                generateEnabled == null ? this.generateEnabled : generateEnabled,
                readEnabled == null ? this.readEnabled : readEnabled,
                rootDir,
                phase1ScanIntervalMillis,
                phase1MinIdleMillis,
                phase1MaxThreadsPerScan,
                phase1OnStartup,
                phase1InputWindowPercent,
                phase1FallbackTokenLimit,
                phase2TriggerOnCandidateCount,
                phase2ScanIntervalMillis,
                phase2MinIntervalMillis,
                phase2MaxCandidates,
                readBudgetTokens);
    }
}
