package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.dto.ContextCompactResult;
import com.wzx.babiq.server.api.dto.ContextSnapshotDto;
import com.wzx.babiq.server.api.dto.ContextStatusResult;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.context.ContextStatusService;
import com.wzx.babiq.server.context.compaction.ContextManualCompactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P3-2 上下文窗口 JSON-RPC handler 测试。
 *
 * <p>桌面端只依赖这两个查询入口了解当前 thread 的窗口状态和历史快照明细，
 * handler 负责参数校验，业务聚合交给 ContextStatusService。</p>
 */
class ContextHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("context/status 按 threadId 返回窗口状态")
    void context_status_should_delegate_to_service() {
        ContextStatusService service = mock(ContextStatusService.class);
        ContextStatusResult status = new ContextStatusResult(
                "thr_ctx", 0, 128_000, 89_600, "ctxsnap_1", 1200, 1300L, 0.0101,
                "ok", "ctxsum_1", 1, "SUCCESS");
        when(service.status("thr_ctx")).thenReturn(status);
        ContextStatusHandler handler = new ContextStatusHandler(service);

        Object result = handler.handle(objectMapper.valueToTree(java.util.Map.of("threadId", "thr_ctx")), null);

        assertThat(handler.method()).isEqualTo("context/status");
        assertThat(result).isEqualTo(status);
    }

    @Test
    @DisplayName("context/snapshot/get 按 snapshotId 返回快照详情")
    void context_snapshot_get_should_delegate_to_service() {
        ContextStatusService service = mock(ContextStatusService.class);
        ContextSnapshotDto snapshot = new ContextSnapshotDto(
                "ctxsnap_1", "thr_ctx", "turn_ctx", "pre_model_call", "deepseek", "deepseek-v4-pro",
                "E:\\BaBiQ", 0, 128_000, 89_600, 1200, null, 1, 0, 0.0093,
                "请总结", "2026-05-26T00:00:00Z", List.of());
        when(service.snapshot("ctxsnap_1")).thenReturn(Optional.of(snapshot));
        ContextSnapshotGetHandler handler = new ContextSnapshotGetHandler(service);

        Object result = handler.handle(objectMapper.valueToTree(java.util.Map.of("snapshotId", "ctxsnap_1")), null);

        assertThat(handler.method()).isEqualTo("context/snapshot/get");
        assertThat(result).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("context/compact 按 threadId 触发手动压缩")
    void context_compact_should_delegate_to_service() {
        ContextManualCompactionService service = mock(ContextManualCompactionService.class);
        ContextCompactResult compactResult = new ContextCompactResult(
                "thr_ctx", "SUCCESS", "ctxsum_1", "ctxcmp_1", 2);
        when(service.compact("thr_ctx")).thenReturn(compactResult);
        ContextCompactHandler handler = new ContextCompactHandler(service);

        Object result = handler.handle(objectMapper.valueToTree(java.util.Map.of("threadId", "thr_ctx")), null);

        assertThat(handler.method()).isEqualTo("context/compact");
        assertThat(result).isEqualTo(compactResult);
    }

    @Test
    @DisplayName("context/status 缺少 threadId 时返回 INVALID_PARAMS")
    void context_status_should_reject_missing_thread_id() {
        ContextStatusHandler handler = new ContextStatusHandler(mock(ContextStatusService.class));

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(java.util.Map.of()), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }

    @Test
    @DisplayName("context/compact 缺少 threadId 时返回 INVALID_PARAMS")
    void context_compact_should_reject_missing_thread_id() {
        ContextCompactHandler handler = new ContextCompactHandler(mock(ContextManualCompactionService.class));

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(java.util.Map.of()), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }
}
