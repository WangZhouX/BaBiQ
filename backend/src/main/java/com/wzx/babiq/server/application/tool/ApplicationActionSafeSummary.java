package com.wzx.babiq.server.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.memory.redaction.MemorySecretRedactor;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 将桌面返回的展示摘要压缩为有界、结构化脱敏文本。 */
final class ApplicationActionSafeSummary {
    private static final String REDACTED = "[REDACTED]";
    private static final String INVALID_JSON = "[REDACTED:invalid-json]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "secret", "token", "apikey", "refreshtoken", "accesstoken",
            "authorization", "cookie", "idcard", "mobile", "phone", "bankcard");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern AUTH = Pattern.compile(
            "(?i)(password|secret|authorization|cookie|api[_-]?key|refresh[_-]?token|access[_-]?token|token)"
                    + "\\s*[:=]\\s*(?!\\[REDACTED(?::[^\\]\\r\\n]+)?\\])"
                    + "(?:Bearer\\s+)?(?:\\[[^\\]\\r\\n]*\\]"
                    + "|\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;]+)");

    private final ObjectMapper json;
    private final MemorySecretRedactor secretRedactor;
    private final int limit;

    ApplicationActionSafeSummary(ObjectMapper json, MemorySecretRedactor secretRedactor, int limit) {
        this.json = json;
        this.secretRedactor = secretRedactor;
        this.limit = limit;
    }

    String sanitize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode parsed = json.readTree(trimmed);
                return bounded(json.writeValueAsString(redact(parsed)));
            } catch (Exception ignored) {
                return INVALID_JSON;
            }
        }
        return bounded(redactScalar(value));
    }

    private JsonNode redact(JsonNode node) {
        if (node.isObject()) {
            var copy = json.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SENSITIVE_KEYS.contains(normalize(field.getKey()))) {
                    copy.put(field.getKey(), REDACTED);
                } else {
                    copy.set(field.getKey(), redactNested(field.getValue()));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            var copy = json.createArrayNode();
            node.forEach(value -> copy.add(redactNested(value)));
            return copy;
        }
        return node.isTextual() ? json.getNodeFactory().textNode(redactScalar(node.asText())) : node.deepCopy();
    }

    private JsonNode redactNested(JsonNode value) {
        if (value != null && value.isTextual()) {
            String text = value.asText().trim();
            if (text.startsWith("{") || text.startsWith("[")) {
                try {
                    return redact(json.readTree(text));
                } catch (Exception ignored) {
                    return json.getNodeFactory().textNode(INVALID_JSON);
                }
            }
        }
        return value == null ? json.nullNode() : redact(value);
    }

    private String redactScalar(String value) {
        String redacted = secretRedactor.redact(value).redactedText();
        redacted = AUTH.matcher(redacted).replaceAll("$1=" + REDACTED);
        redacted = ID_CARD.matcher(redacted).replaceAll(REDACTED);
        redacted = PHONE.matcher(redacted).replaceAll(REDACTED);
        return BANK_CARD.matcher(redacted).replaceAll(REDACTED);
    }

    private String normalize(String key) {
        return key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private String bounded(String value) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
