package com.wzx.babiq.server.tool;

import com.wzx.babiq.server.capability.CapabilityDescriptor;
import com.wzx.babiq.server.capability.CapabilityExposureMode;
import com.wzx.babiq.server.capability.CapabilitySearchRequest;
import com.wzx.babiq.server.capability.CapabilitySearchResult;
import com.wzx.babiq.server.capability.CapabilitySearchService;
import com.wzx.babiq.server.capability.CapabilityType;
import com.wzx.babiq.server.tool.impl.ToolSearchTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tool_search 工具测试。
 *
 * <p>该工具本身仍是 BaBiQ 普通 Tool，因此会继续经过现有工具调用链路；
 * 测试固定它对模型返回的是可读 JSON，而不是直接执行或绕过 ToolRegistry。</p>
 */
class ToolSearchToolTest {

    @Test
    @DisplayName("tool_search 调用能力搜索服务并返回命中能力 JSON")
    void tool_search_should_return_capability_metadata() {
        AtomicReference<CapabilitySearchRequest> captured = new AtomicReference<>();
        CapabilitySearchService service = request -> {
            captured.set(request);
            return new CapabilitySearchResult("LUCENE", List.of(new CapabilityDescriptor(
                    "mcp.files.read_file",
                    CapabilityType.MCP_TOOL,
                    "files",
                    "read_file",
                    "read_file",
                    "读取 MCP 文件",
                    "files",
                    "hash",
                    "读取 MCP 文件",
                    CapabilityExposureMode.DEFERRED,
                    true,
                    Instant.parse("2026-05-27T00:00:00Z"))));
        };
        ToolSearchTool tool = new ToolSearchTool(service);

        ToolResult result = tool.search("read file", 3, null);

        assertThat(tool.name()).isEqualTo("tool_search");
        assertThat(captured.get().queryText()).isEqualTo("read file");
        assertThat(captured.get().limit()).isEqualTo(3);
        assertThat(result.ok()).isTrue();
        assertThat(result.output()).contains("mcp.files.read_file").contains("读取 MCP 文件");
    }
}
