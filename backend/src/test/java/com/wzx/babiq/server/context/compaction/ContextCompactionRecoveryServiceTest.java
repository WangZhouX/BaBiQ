package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.repository.ContextCompactionRecord;
import com.wzx.babiq.server.context.repository.ContextCompactionRepository;
import com.wzx.babiq.server.context.repository.ContextWindowRecord;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextCompactionRecoveryService 启动恢复测试。
 *
 * <p>P3-3a 要保证压缩链路跨进程崩溃后仍可解释：没有被 active window 安装的成功记录要转成
 * ORPHANED，半开始未结束的记录要转成 INTERRUPTED，而不是让 UI 误以为压缩已经生效。</p>
 */
class ContextCompactionRecoveryServiceTest {

    @Test
    @DisplayName("SUCCESS 但 active window 未指向该摘要时标记为 ORPHANED")
    void scan_should_mark_success_record_orphaned_when_window_does_not_reference_summary() {
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextCompactionRecord orphan = record("ctxcmp_1", "SUCCESS", "ctxsum_1", null, Instant.now());
        when(compactionRepository.findRecoverableSince(any())).thenReturn(List.of(orphan));
        when(windowRepository.findByThreadId("thr_1")).thenReturn(Optional.of(new ContextWindowRecord(
                "thr_1", 1, "ctxsum_other", 128_000, 96_000, "ctxsnap_1", Instant.now(), Instant.now())));
        ContextCompactionRecoveryService service = new ContextCompactionRecoveryService(
                compactionRepository, windowRepository);

        CompactionRecoveryReport report = service.scan();

        verify(compactionRepository).updateStatus(
                "ctxcmp_1", "ORPHANED", "压缩摘要未被当前窗口安装", orphan.completedAt());
        assertThat(report.orphanedCompactions()).isEqualTo(1);
    }

    @Test
    @DisplayName("SUCCESS 且 active window 已指向该摘要时保持不变")
    void scan_should_leave_installed_success_record_unchanged() {
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextCompactionRecord installed = record("ctxcmp_1", "SUCCESS", "ctxsum_1", null, Instant.now());
        when(compactionRepository.findRecoverableSince(any())).thenReturn(List.of(installed));
        when(windowRepository.findByThreadId("thr_1")).thenReturn(Optional.of(new ContextWindowRecord(
                "thr_1", 1, "ctxsum_1", 128_000, 96_000, "ctxsnap_1", Instant.now(), Instant.now())));
        ContextCompactionRecoveryService service = new ContextCompactionRecoveryService(
                compactionRepository, windowRepository);

        CompactionRecoveryReport report = service.scan();

        verify(compactionRepository, never()).updateStatus(any(), any(), any(), any());
        assertThat(report.installedCompactions()).isEqualTo(1);
    }

    @Test
    @DisplayName("started_at 非空但 completed_at 为空时标记为 INTERRUPTED")
    void scan_should_mark_unfinished_record_interrupted() {
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextCompactionRecord interrupted = record("ctxcmp_1", "RUNNING", null, Instant.now(), null);
        when(compactionRepository.findRecoverableSince(any())).thenReturn(List.of(interrupted));
        ContextCompactionRecoveryService service = new ContextCompactionRecoveryService(
                compactionRepository, windowRepository);

        CompactionRecoveryReport report = service.scan();

        verify(compactionRepository).updateStatus(
                "ctxcmp_1", "INTERRUPTED", "服务重启时压缩流程尚未完成", null);
        assertThat(report.interruptedCompactions()).isEqualTo(1);
    }

    @Test
    @DisplayName("空数据库安全返回空恢复报告")
    void scan_should_return_empty_report_for_empty_database() {
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        when(compactionRepository.findRecoverableSince(any())).thenReturn(List.of());
        ContextCompactionRecoveryService service = new ContextCompactionRecoveryService(
                compactionRepository, windowRepository);

        CompactionRecoveryReport report = service.scan();

        assertThat(report.scannedCompactions()).isZero();
        assertThat(report.interruptedCompactions()).isZero();
        assertThat(report.orphanedCompactions()).isZero();
    }

    private static ContextCompactionRecord record(String compactionId,
                                                  String status,
                                                  String summaryId,
                                                  Instant startedAt,
                                                  Instant completedAt) {
        Instant now = Instant.now();
        return new ContextCompactionRecord(
                compactionId,
                "thr_1",
                "turn_1",
                status,
                summaryId,
                "it_1..it_2",
                "it_1",
                "it_2",
                100,
                10,
                null,
                now,
                "AUTO_PRE_TURN",
                0,
                1,
                "ctxsnap_in",
                "ctxsnap_out",
                128_000,
                96_000,
                72_000,
                startedAt,
                completedAt);
    }
}
