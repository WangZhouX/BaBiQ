package com.wzx.babiq.server.application.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.memory.redaction.MemorySecretRedactor;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 对桌面返回的非可信摘要做递归脱敏，并在截断前消除凭据和个人身份信息。 */
@Component
public final class ApplicationActionRedactor {
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

    public ApplicationActionRedactor(ObjectMapper json, MemorySecretRedactor secretRedactor) {
        this.json = json;
        this.secretRedactor = secretRedactor;
    }

    /** 脱敏后限制长度；JSON 输入会递归处理敏感键和嵌套字符串。 */
    public String sanitize(String value, int limit) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode parsed = json.readTree(trimmed);
                return bounded(json.writeValueAsString(redact(parsed)), limit);
            } catch (Exception ignored) {
                return INVALID_JSON;
            }
        }
        return bounded(redactScalar(value), limit);
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

    private static String normalize(String key) {
        return key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String bounded(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
