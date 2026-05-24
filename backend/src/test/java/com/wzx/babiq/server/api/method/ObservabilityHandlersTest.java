package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.observability.LocalObservabilityService;
import com.wzx.babiq.server.observability.LocalObservabilitySnapshot;
import com.wzx.babiq.server.observability.ModelUsageStats;
import com.wzx.babiq.server.observability.ObservabilityCostsResult;
import com.wzx.babiq.server.observability.ObservabilityToolsResult;
import com.wzx.babiq.server.observability.ObservabilityTotals;
import com.wzx.babiq.server.observability.StatusStats;
import com.wzx.babiq.server.observability.ToolStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-5 本地可观测 JSON-RPC handler 测试。
 *
 * <p>handler 是桌面端和后端统计服务之间的协议边界。这里不测试 SQL 聚合，
 * 只约束参数默认值、错误码映射和响应包装，避免 UI 直接依赖 service 内部细节。</p>
 */
class ObservabilityHandlersTest {

    /** 测试用 JSON 转换器，用来模拟 dispatcher 传入的 params JsonNode。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("observability/snapshot 默认查询 7d 并透传 cwd")
    void snapshot_should_default_to_seven_days_and_delegate_cwd() {
        LocalObservabilityService service = mock(LocalObservabilityService.class);
        LocalObservabilitySnapshot expected = sampleSnapshot("7d");
        when(service.snapshot("7d", "E:\\BaBiQ")).thenReturn(expected);
        ObservabilitySnapshotHandler handler = new ObservabilitySnapshotHandler(service);

        Object result = handler.handle(objectMapper.valueToTree(Map.of("cwd", "E:\\BaBiQ")), null);

        assertThat(handler.method()).isEqualTo("observability/snapshot");
        assertThat(result).isSameAs(expected);
        verify(service).snapshot("7d", "E:\\BaBiQ");
    }

    @Test
    @DisplayName("observability/tools 返回 range 和工具聚合列表")
    void tools_should_wrap_tool_stats_with_range() {
        LocalObservabilityService service = mock(LocalObservabilityService.class);
        List<ToolStats> tools = List.of(new ToolStats("exec_shell", 2, 1, 1200));
        when(service.tools("30d", null)).thenReturn(tools);
        ObservabilityToolsHandler handler = new ObservabilityToolsHandler(service);

        Object result = handler.handle(objectMapper.valueToTree(Map.of("range", "30d")), null);

        assertThat(result).isEqualTo(new ObservabilityToolsResult("30d", tools));
        verify(service).tools("30d", null);
    }

    @Test
    @DisplayName("observability/costs 返回 range 和模型 token 用量列表")
    void costs_should_wrap_model_cost_stats_with_range() {
        LocalObservabilityService service = mock(LocalObservabilityService.class);
        List<ModelUsageStats> models = List.of(sampleModel("deepseek", "deepseek-v4-pro"));
        when(service.costs("all", "E:\\BaBiQ")).thenReturn(models);
        ObservabilityCostsHandler handler = new ObservabilityCostsHandler(service);

        Object result = handler.handle(objectMapper.valueToTree(Map.of(
                "range", "all",
                "cwd", "E:\\BaBiQ")), null);

        assertThat(result).isEqualTo(new ObservabilityCostsResult("all", models));
        verify(service).costs("all", "E:\\BaBiQ");
    }

    @Test
    @DisplayName("非法 range 映射为 JSON-RPC INVALID_PARAMS")
    void invalid_range_should_be_mapped_to_invalid_params() {
        LocalObservabilityService service = mock(LocalObservabilityService.class);
        when(service.snapshot("bad", null)).thenThrow(new IllegalArgumentException("非法统计窗口: bad"));
        ObservabilitySnapshotHandler handler = new ObservabilitySnapshotHandler(service);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of("range", "bad")), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }

    private static LocalObservabilitySnapshot sampleSnapshot(String range) {
        ModelUsageStats model = sampleModel("deepseek", "deepseek-v4-pro");
        return new LocalObservabilitySnapshot(
                range,
                new ObservabilityTotals(1, 0, 100, 40, 140),
                List.of(new ModelUsageStats("deepseek", null, 1, 0, 100, 40, 140)),
                List.of(model),
                List.of(new ToolStats("read_file", 1, 0, 300)),
                List.of(new StatusStats("COMPLETED", 1)));
    }

    private static ModelUsageStats sampleModel(String providerId, String model) {
        return new ModelUsageStats(providerId, model, 1, 0, 100, 40, 140);
    }
}
