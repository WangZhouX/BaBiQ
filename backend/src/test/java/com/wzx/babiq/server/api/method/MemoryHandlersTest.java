package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.dto.MemoryArtifactInfo;
import com.wzx.babiq.server.api.dto.MemoryArtifactsListResult;
import com.wzx.babiq.server.api.dto.MemoryConsolidateResult;
import com.wzx.babiq.server.api.dto.MemoryJobInfo;
import com.wzx.babiq.server.api.dto.MemoryJobsListResult;
import com.wzx.babiq.server.api.dto.MemorySettingsSetResult;
import com.wzx.babiq.server.api.dto.MemoryStatusResult;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.context.model.LongTermMemoryReference;
import com.wzx.babiq.server.memory.LongTermMemoryPipeline;
import com.wzx.babiq.server.memory.MemoryStatusService;
import com.wzx.babiq.server.memory.retrieval.LongTermMemoryRetrievalResult;
import com.wzx.babiq.server.memory.retrieval.LongTermMemoryRetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 长期记忆 JSON-RPC handler 测试。
 *
 * <p>桌面端不会直接访问 SQLite 或 Markdown 文件，只通过这些最小 RPC 了解状态、
 * 调整开关和触发归并。</p>
 */
class MemoryHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("memory/status 返回长期记忆状态摘要")
    void memory_status_should_delegate_to_service() throws Exception {
        MemoryStatusService service = mock(MemoryStatusService.class);
        MemoryStatusResult expected = new MemoryStatusResult(true, true, true, true,
                "E:\\BaBiQ\\.babiq\\memories", 1, 0, 5, "memart_1", "2026-05-27T00:00:00Z", 2);
        when(service.status()).thenReturn(expected);
        MemoryStatusHandler handler = new MemoryStatusHandler(service);

        Object result = handler.handle(objectMapper.nullNode(), null);

        assertThat(handler.method()).isEqualTo("memory/status");
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("memory/search 返回长期记忆检索引用")
    void memory_search_should_return_retrieval_references() throws Exception {
        LongTermMemoryRetrievalService service = mock(LongTermMemoryRetrievalService.class);
        when(service.retrieve(null, null, null, "权限", 32768)).thenReturn(new LongTermMemoryRetrievalResult(
                List.of(new LongTermMemoryReference("memart_1", "medium", "权限切换需要进入 Agent 运行时")),
                20));
        MemorySearchHandler handler = new MemorySearchHandler(service, new ApproximateContextTokenEstimator());

        Object result = handler.handle(objectMapper.valueToTree(java.util.Map.of("query", "权限")), null);

        assertThat(handler.method()).isEqualTo("memory/search");
        assertThat(result).isInstanceOf(com.wzx.babiq.server.api.dto.MemorySearchResult.class);
    }

    @Test
    @DisplayName("memory/settings/set 支持局部修改长期记忆开关")
    void memory_settings_set_should_accept_partial_update() throws Exception {
        MemoryStatusService service = mock(MemoryStatusService.class);
        MemorySettingsSetResult expected = new MemorySettingsSetResult(true, false, true, true);
        when(service.updateSettings(null, false, null, null)).thenReturn(expected);
        MemorySettingsSetHandler handler = new MemorySettingsSetHandler(service);

        Object result = handler.handle(objectMapper.valueToTree(java.util.Map.of("generateEnabled", false)), null);

        assertThat(handler.method()).isEqualTo("memory/settings/set");
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("memory/jobs/list 和 memory/artifacts/list 读取最近审计数据")
    void memory_list_handlers_should_return_recent_records() throws Exception {
        MemoryStatusService service = mock(MemoryStatusService.class);
        when(service.jobs(20)).thenReturn(new MemoryJobsListResult(List.of(
                new MemoryJobInfo("memjob_1", "PHASE2", "phase2:1", 1, "PENDING", "2026-05-27T00:00:00Z"))));
        when(service.artifacts(20)).thenReturn(new MemoryArtifactsListResult(List.of(
                new MemoryArtifactInfo("memart_1", "MEMORY_SUMMARY", "memory_summary.md", 1, 100,
                        "2026-05-27T00:00:00Z"))));

        Object jobs = new MemoryJobsListHandler(service)
                .handle(objectMapper.valueToTree(java.util.Map.of("limit", 20)), null);
        Object artifacts = new MemoryArtifactsListHandler(service)
                .handle(objectMapper.valueToTree(java.util.Map.of("limit", 20)), null);

        assertThat(jobs).isInstanceOf(MemoryJobsListResult.class);
        assertThat(artifacts).isInstanceOf(MemoryArtifactsListResult.class);
    }

    @Test
    @DisplayName("memory/consolidate 支持 force 参数并返回创建的 Phase2 job")
    void memory_consolidate_should_delegate_to_pipeline() throws Exception {
        LongTermMemoryPipeline pipeline = mock(LongTermMemoryPipeline.class);
        MemoryConsolidateResult expected = new MemoryConsolidateResult(true, "memjob_2", 2, "QUEUED");
        when(pipeline.consolidate(true)).thenReturn(expected);
        MemoryConsolidateHandler handler = new MemoryConsolidateHandler(pipeline);

        Object result = handler.handle(objectMapper.valueToTree(java.util.Map.of("force", true)), null);

        assertThat(handler.method()).isEqualTo("memory/consolidate");
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("memory/jobs/list 拒绝小于 1 的 limit")
    void memory_jobs_list_should_reject_invalid_limit() {
        MemoryJobsListHandler handler = new MemoryJobsListHandler(mock(MemoryStatusService.class));

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(java.util.Map.of("limit", 0)), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }
}
