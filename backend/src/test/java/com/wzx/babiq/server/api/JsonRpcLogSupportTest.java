package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

    @Test
    @DisplayName("应用动作参数摘要始终固定脱敏")
    void application_action_params_should_never_enter_logs() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("input", "secret-action-payload");

        String summary = JsonRpcLogSupport.paramsSummary("application/action/request", params);

        assertThat(summary).isEqualTo("[application-action-redacted]");
        assertThat(summary).doesNotContain("secret-action-payload");
    }

    @Test
    @DisplayName("附件路径字段会递归脱敏且保留安全标识和名称")
    void attachment_paths_should_be_recursively_redacted_without_hiding_safe_metadata() {
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode input = params.putObject("input");
        ArrayNode attachments = input.putArray("attachments");
        attachments.addObject()
                .put("id", "550e8400-e29b-41d4-a716-446655440000")
                .put("displayId", "A-7K3M2Q")
                .put("name", "contract.pdf")
                .put("localPath", "C:\\private\\customer\\contract.pdf")
                .put("pathLabel", "selected-file")
                .putObject("diagnostic")
                .put("canonicalPath", "C:\\private\\customer\\canonical-contract.pdf")
                .put("internalCachePath", "C:\\private\\cache\\contract.pdf");

        String summary = JsonRpcLogSupport.paramsSummary(params);

        assertThat(summary)
                .contains("\"id\":\"550e8400-e29b-41d4-a716-446655440000\"")
                .contains("\"displayId\":\"A-7K3M2Q\"")
                .contains("\"name\":\"contract.pdf\"")
                .contains("\"pathLabel\":\"selected-file\"")
                .contains("<local-path-redacted>")
                .doesNotContain("C:\\private")
                .doesNotContain("canonical-contract.pdf");
        assertThat(params.path("input").path("attachments").path(0).path("localPath").asText())
                .isEqualTo("C:\\private\\customer\\contract.pdf");
    }

    @Test
    @DisplayName("附件路径键会归一化全部分隔符并穿透嵌套数组脱敏")
    void attachment_path_key_normalization_should_cover_separator_and_case_variants() {
        ObjectNode params = objectMapper.createObjectNode();
        ArrayNode batches = params.putArray("batches");
        batches.addObject()
                .put("name", "contract.pdf")
                .put("pathLabel", "selected-file")
                .put("pathCount", 4)
                .put("LOCAL.PATH", "C:\\private\\dot-secret.pdf")
                .put("local path", "C:\\private\\space-secret.pdf")
                .put("Local/File\\Path", "C:\\private\\mixed-secret.pdf")
                .putArray("nested")
                .addObject()
                .put("localFilePath", "C:\\private\\local-file-secret.pdf")
                .put("Canonical File Path", "C:\\private\\canonical-secret.pdf")
                .put("x.INTERNAL-cache.path", "C:\\private\\internal-secret.pdf");

        String summary = JsonRpcLogSupport.paramsSummary(params);

        assertThat(summary)
                .contains("\"name\":\"contract.pdf\"")
                .contains("\"pathLabel\":\"selected-file\"")
                .contains("\"pathCount\":4")
                .contains("<local-path-redacted>")
                .doesNotContain("C:\\private")
                .doesNotContain("dot-secret.pdf")
                .doesNotContain("space-secret.pdf")
                .doesNotContain("mixed-secret.pdf")
                .doesNotContain("local-file-secret.pdf")
                .doesNotContain("canonical-secret.pdf")
                .doesNotContain("internal-secret.pdf");
    }
}
