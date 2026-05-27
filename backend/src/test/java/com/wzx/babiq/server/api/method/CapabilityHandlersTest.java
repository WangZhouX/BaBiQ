package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.dto.CapabilityInfo;
import com.wzx.babiq.server.api.dto.CapabilitySearchRpcResult;
import com.wzx.babiq.server.api.dto.CapabilitySettingsSetResult;
import com.wzx.babiq.server.api.dto.CapabilityStatusResult;
import com.wzx.babiq.server.capability.CapabilityCatalogService;
import com.wzx.babiq.server.capability.CapabilityExposureMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P3-5 能力目录 JSON-RPC handler 测试。
 */
class CapabilityHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("capability/status 返回统一能力目录摘要")
    void status_should_delegate_to_service() {
        CapabilityCatalogService service = mock(CapabilityCatalogService.class);
        CapabilityInfo info = info("local.exec_shell", "VISIBLE", true);
        CapabilityStatusResult expected = new CapabilityStatusResult(1, 1, 1, 0, 0, List.of(info));
        when(service.status()).thenReturn(expected);

        Object result = new CapabilityStatusHandler(service).handle(objectMapper.nullNode(), null);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("capability/search 支持 UI 查询能力 metadata")
    void search_should_accept_query_and_limit() {
        CapabilityCatalogService service = mock(CapabilityCatalogService.class);
        CapabilitySearchRpcResult expected = new CapabilitySearchRpcResult(
                "FALLBACK_LEXICAL", List.of(info("mcp.fs.read", "DEFERRED", true)));
        when(service.search("file", 4, false)).thenReturn(expected);

        Object result = new CapabilitySearchHandler(service)
                .handle(objectMapper.valueToTree(Map.of("query", "file", "limit", 4)), null);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("capability/settings/set 支持切换暴露模式")
    void settings_should_update_capability_mode() {
        CapabilityCatalogService service = mock(CapabilityCatalogService.class);
        CapabilitySettingsSetResult expected = new CapabilitySettingsSetResult(
                info("mcp.fs.read", "VISIBLE", true));
        when(service.updateSettings("mcp.fs.read", true, CapabilityExposureMode.VISIBLE)).thenReturn(expected);

        Object result = new CapabilitySettingsSetHandler(service).handle(objectMapper.valueToTree(Map.of(
                "capabilityId", "mcp.fs.read",
                "enabled", true,
                "exposureMode", "VISIBLE")), null);

        assertThat(result).isEqualTo(expected);
    }

    private static CapabilityInfo info(String capabilityId, String exposureMode, boolean enabled) {
        return new CapabilityInfo(capabilityId, "MCP_TOOL", "mcp", "read", "read",
                "读取文件", exposureMode, enabled, "2026-05-27T00:00:00Z");
    }
}
