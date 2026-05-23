package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON-RPC 日志摘要工具测试。
 *
 * <p>后端日志要足够帮助定位问题,但不能把 api-key、token 或超长用户输入完整打到控制台。</p>
 */
class JsonRpcLogSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("参数摘要会隐藏敏感字段并截断长文本")
    void params_summary_should_mask_sensitive_fields_and_truncate_long_text() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("providerId", "deepseek-official");
        params.put("apiKey", "sk-secret");
        params.put("promptTokens", 123);
        params.putObject("input").put("text", "你".repeat(180));

        String summary = JsonRpcLogSupport.paramsSummary(params);

        assertThat(summary)
                .contains("deepseek-official")
                .contains("\"promptTokens\":123")
                .contains("***")
                .contains("...");
        assertThat(summary).doesNotContain("sk-secret");
        assertThat(summary.length()).isLessThan(360);
    }
}
