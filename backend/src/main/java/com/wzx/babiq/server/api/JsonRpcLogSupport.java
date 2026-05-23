package com.wzx.babiq.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JSON-RPC 日志摘要工具。
 *
 * <p>调试 WebSocket 协议时,我们需要看到 requestId、method、参数摘要和耗时。
 * 但日志不能泄漏 API key、token,也不能把超长用户输入完整刷屏。本类集中处理脱敏和截断,
 * 让各层日志保持一致。</p>
 */
public final class JsonRpcLogSupport {

    /** 日志摘要专用 ObjectMapper，只做安全序列化，不参与业务请求反序列化。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 文本字段预览长度上限，避免用户长 prompt 把控制台刷屏。 */
    private static final int TEXT_PREVIEW_LIMIT = 120;
    /** JSON 摘要长度上限，保护日志可读性并降低敏感数据泄漏面。 */
    private static final int JSON_SUMMARY_LIMIT = 280;

    private JsonRpcLogSupport() {
    }

    /**
     * 返回 JSON-RPC params 的安全摘要。
     *
     * @param params 原始 params 节点
     * @return 已脱敏、已截断的 JSON 摘要
     */
    public static String paramsSummary(JsonNode params) {
        if (params == null || params.isNull()) {
            return "null";
        }
        JsonNode sanitized = sanitize(params);
        try {
            return abbreviate(OBJECT_MAPPER.writeValueAsString(sanitized), JSON_SUMMARY_LIMIT);
        } catch (JsonProcessingException exception) {
            return "<params-summary-error:" + exception.getMessage() + ">";
        }
    }

    /**
     * 截断普通文本,适合 prompt、失败原因和响应摘要。
     *
     * @param value 原始文本
     * @return 单行安全预览
     */
    public static String preview(String value) {
        if (value == null) {
            return "null";
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return abbreviate(singleLine, TEXT_PREVIEW_LIMIT);
    }

    /**
     * 计算纳秒起点到当前的毫秒耗时。
     *
     * @param startedNanos System.nanoTime 起点
     * @return 毫秒耗时
     */
    public static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static JsonNode sanitize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = OBJECT_MAPPER.createObjectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                // 字段名命中敏感词时保留 key、替换 value，既能排查字段存在，也不泄漏密钥。
                if (isSensitiveField(field.getKey())) {
                    copy.set(field.getKey(), TextNode.valueOf("***"));
                } else {
                    copy.set(field.getKey(), sanitize(field.getValue()));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = OBJECT_MAPPER.createArrayNode();
            node.forEach(child -> copy.add(sanitize(child)));
            return copy;
        }
        if (node.isTextual()) {
            // 用户输入和工具参数可能很长，文本节点统一截断为单行预览。
            return TextNode.valueOf(preview(node.asText()));
        }
        return node;
    }

    /**
     * 判断字段名是否属于敏感字段。
     */
    private static boolean isSensitiveField(String fieldName) {
        String normalized = fieldName.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");
        return normalized.contains("apikey")
                || normalized.contains("authorization")
                || normalized.contains("password")
                || normalized.contains("secret")
                || "token".equals(normalized)
                || "accesstoken".equals(normalized)
                || "refreshtoken".equals(normalized);
    }

    /**
     * 通用字符串截断。
     */
    private static String abbreviate(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit - 3)) + "...";
    }
}
