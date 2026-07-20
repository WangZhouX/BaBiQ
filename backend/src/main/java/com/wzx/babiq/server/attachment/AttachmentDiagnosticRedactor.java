package com.wzx.babiq.server.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Locale;
import java.util.Map;

/**
 * Structural redaction for attachment-bearing diagnostic JSON.
 *
 * <p>The returned tree is a copy. Safe attachment metadata remains available for diagnostics,
 * while local, canonical and internal path fields are replaced before serialization.</p>
 */
public final class AttachmentDiagnosticRedactor {

    public static final String REDACTED_PATH = "<local-path-redacted>";

    private AttachmentDiagnosticRedactor() {
    }

    public static JsonNode redact(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                copy.set(
                        field.getKey(),
                        isPathBearingField(field.getKey())
                                ? TextNode.valueOf(REDACTED_PATH)
                                : redact(field.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> copy.add(redact(child)));
            return copy;
        }
        return node.deepCopy();
    }

    private static boolean isPathBearingField(String fieldName) {
        String normalized = fieldName.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");
        return "localpath".equals(normalized)
                || ((normalized.contains("canonical") || normalized.contains("internal"))
                && normalized.endsWith("path"));
    }
}
