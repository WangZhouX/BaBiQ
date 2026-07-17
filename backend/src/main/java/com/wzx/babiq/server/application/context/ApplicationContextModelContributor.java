package com.wzx.babiq.server.application.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 把冻结业务身份对应的动作摘要和页面事实转换为有界不可信模型参考。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class ApplicationContextModelContributor {

    private static final int MAX_ACTION_SCHEMA_BYTES = 16 * 1024;
    private static final int MAX_RENDERED_BYTES = 64 * 1024;
    private static final Pattern ACRONYM_TO_WORD_BOUNDARY = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    private static final Pattern LOWER_TO_UPPER_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Set<String> CHINESE_CREDENTIAL_MARKERS = Set.of(
            "\u5bc6\u7801", "\u53e3\u4ee4", "\u4ee4\u724c", "\u5bc6\u94a5", "\u51ed\u8bc1");
    private static final Set<String> CREDENTIAL_WORDS = Set.of(
            "password", "passwd", "pwd", "token", "secret", "authorization", "bearer",
            "credential", "credentials");
    private static final Set<String> COMPACT_CREDENTIAL_COMPOUNDS = Set.of(
            "apitoken", "idtoken", "accesstoken", "refreshtoken", "bearertoken",
            "clientsecret", "apikey", "accesskey", "privatekey", "secretkey",
            "signingkey", "encryptionkey");

    private final BusinessIdentityScopeService scopes;
    private final ApplicationCatalogRegistry catalogs;
    private final ApplicationPageContextRegistry contexts;
    private final ObjectMapper json;

    public ApplicationContextModelContributor(BusinessIdentityScopeService scopes,
                                              ApplicationCatalogRegistry catalogs,
                                              ApplicationPageContextRegistry contexts,
                                              ObjectMapper json) {
        this.scopes = scopes;
        this.catalogs = catalogs;
        this.contexts = contexts;
        this.json = json == null ? new ObjectMapper() : json.copy();
    }

    /** 缺失、漂移或超预算时返回空列表，禁止回退到其他身份或原始 payload。 */
    public List<String> contribute(BusinessIdentityScope scope) {
        if (scope == null || !scope.scoped()) {
            return List.of();
        }
        try {
            return scopes.withActiveConnectionScope(scope, active -> {
                var catalog = catalogs.current(active.connection()).orElse(null);
                var context = contexts.current(active.connection()).orElse(null);
                if (catalog == null || context == null || catalog.catalogEpoch() != context.catalogEpoch()) {
                    return List.<String>of();
                }
                ObjectNode contribution = json.createObjectNode();
                contribution.put("catalogEpoch", catalog.catalogEpoch());
                contribution.put("contextSequence", context.contextSequence());
                contribution.set("actions", actionSummaries(catalog.payload().path("actions")));
                contribution.set("pageContext", sanitizePageContext(
                        context.payload(), catalog.payload().path("actions")));
                String rendered;
                try {
                    rendered = "<untrusted-data source=\"business_application\">"
                            + escapeBoundaryText(json.writeValueAsString(contribution)) + "</untrusted-data>";
                } catch (Exception failure) {
                    return List.<String>of();
                }
                if (rendered.getBytes(StandardCharsets.UTF_8).length > MAX_RENDERED_BYTES) {
                    return List.<String>of();
                }
                return List.of(rendered);
            }).orElseGet(List::of);
        } catch (RuntimeException identityTransition) {
            return List.of();
        }
    }

    private ArrayNode actionSummaries(JsonNode actions) {
        ArrayNode summaries = json.createArrayNode();
        if (actions.isObject()) {
            actions.fields().forEachRemaining(entry -> addActionSummary(summaries, entry.getKey(), entry.getValue()));
        } else if (actions.isArray()) {
            actions.forEach(action -> addActionSummary(summaries, text(action, "id"), action));
        }
        return summaries;
    }

    private void addActionSummary(ArrayNode summaries, String fallbackId, JsonNode action) {
        if (action == null || !action.isObject() || action.path("enabled").isBoolean()
                && !action.path("enabled").booleanValue()) {
            return;
        }
        if (!schemaWithinBudget(action)) {
            return;
        }
        String id = text(action, "id");
        if (id == null) {
            id = fallbackId;
        }
        Integer version = positiveInt(action.get("version"));
        String title = boundedText(action, "title", 256);
        String description = boundedText(action, "description", 512);
        String risk = boundedText(action, "risk", 64);
        if (blank(id) || version == null || blank(title) || blank(risk)) {
            return;
        }
        ObjectNode summary = summaries.addObject().put("id", id).put("version", version)
                .put("title", title).put("risk", risk);
        if (!blank(description)) {
            summary.put("description", description);
        }
    }

    private JsonNode sanitizePageContext(JsonNode source, JsonNode catalogActions) {
        JsonNode sanitized = sanitize(source, null, 0);
        if (sanitized.isObject() && source != null && source.isObject() && source.has("availableActions")) {
            ((ObjectNode) sanitized).set("availableActions",
                    pageActionSummaries(source.path("availableActions"), catalogActions));
        }
        return sanitized;
    }

    private ArrayNode pageActionSummaries(JsonNode pageActions, JsonNode catalogActions) {
        ArrayNode summaries = json.createArrayNode();
        if (!pageActions.isArray()) {
            return summaries;
        }
        for (JsonNode pageAction : pageActions) {
            String id = text(pageAction, "id");
            JsonNode catalogAction = findCatalogAction(catalogActions, id);
            if (pageAction == null || !pageAction.isObject() || blank(id)
                    || pageAction.path("enabled").isBoolean() && !pageAction.path("enabled").booleanValue()
                    || catalogAction == null || !schemaWithinBudget(catalogAction)
                    || !schemaWithinBudget(pageAction)) {
                continue;
            }
            String title = boundedText(pageAction, "title", 256);
            String description = boundedText(pageAction, "description", 512);
            Integer version = positiveInt(catalogAction.get("version"));
            String risk = boundedText(catalogAction, "risk", 64);
            if (blank(title) || version == null || blank(risk)) {
                continue;
            }
            ObjectNode summary = summaries.addObject().put("id", id).put("version", version)
                    .put("title", title).put("risk", risk);
            if (!blank(description)) {
                summary.put("description", description);
            }
        }
        return summaries;
    }

    private JsonNode findCatalogAction(JsonNode catalogActions, String id) {
        if (blank(id)) {
            return null;
        }
        if (catalogActions.isObject()) {
            JsonNode action = catalogActions.get(id);
            return action != null && action.isObject() ? action : null;
        }
        if (catalogActions.isArray()) {
            for (JsonNode action : catalogActions) {
                if (id.equals(text(action, "id"))) {
                    return action;
                }
            }
        }
        return null;
    }

    private JsonNode sanitize(JsonNode node, String key, int depth) {
        if (node == null || node.isNull() || depth > 12 || credentialKey(key)) {
            return json.nullNode();
        }
        if (node.isObject()) {
            String sensitivity = text(node, "sensitivity");
            if ("secret".equalsIgnoreCase(sensitivity)) {
                return json.nullNode();
            }
            if (credentialDescriptorObject(node)) {
                return json.nullNode();
            }
            if ("sensitive".equalsIgnoreCase(sensitivity)) {
                ObjectNode masked = (ObjectNode) node.deepCopy();
                if (masked.has("value")) {
                    masked.put("value", "[MASKED]");
                }
                if (masked.has("validationErrors")) {
                    ArrayNode messages = json.createArrayNode();
                    masked.path("validationErrors").forEach(ignored -> messages.add("[MASKED]"));
                    masked.set("validationErrors", messages);
                }
                node = masked;
            }
            ObjectNode result = json.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("availableActions".equals(field.getKey())
                        || credentialKey(field.getKey()) || disabledAction(field.getValue())) {
                    continue;
                }
                JsonNode sanitized = sanitize(field.getValue(), field.getKey(), depth + 1);
                if (!sanitized.isNull()) {
                    result.set(field.getKey(), sanitized);
                }
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            for (JsonNode element : node) {
                if (!disabledAction(element)) {
                    JsonNode sanitized = sanitize(element, key, depth + 1);
                    if (!sanitized.isNull()) {
                        result.add(sanitized);
                    }
                }
            }
            return result;
        }
        if (node.isTextual()) {
            String value = node.textValue();
            return json.getNodeFactory().textNode(value.length() <= 2_000 ? value : value.substring(0, 2_000));
        }
        return node.deepCopy();
    }

    private static boolean disabledAction(JsonNode node) {
        return node != null && node.isObject() && node.path("enabled").isBoolean()
                && !node.path("enabled").booleanValue();
    }

    private static boolean credentialDescriptorObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        for (String field : List.of("id", "label", "type")) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && credentialKey(value.textValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean credentialKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = Normalizer.normalize(key, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (CHINESE_CREDENTIAL_MARKERS.stream().anyMatch(normalized::contains)) {
            return true;
        }
        String compact = normalized.codePoints()
                .filter(Character::isLetterOrDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        if (COMPACT_CREDENTIAL_COMPOUNDS.contains(compact)) {
            return true;
        }
        String words = ACRONYM_TO_WORD_BOUNDARY.matcher(
                Normalizer.normalize(key, Normalizer.Form.NFKC)).replaceAll("$1_$2");
        words = LOWER_TO_UPPER_BOUNDARY.matcher(words).replaceAll("$1_$2").toLowerCase(Locale.ROOT);
        return Stream.of(NON_ALPHANUMERIC.split(words))
                .filter(word -> !word.isBlank())
                .anyMatch(CREDENTIAL_WORDS::contains);
    }

    private static boolean schemaWithinBudget(JsonNode action) {
        JsonNode schema = action == null ? null : action.get("inputSchema");
        return schema == null
                || schema.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_ACTION_SCHEMA_BYTES;
    }

    private static String escapeBoundaryText(String value) {
        return value.replace("&", "\\u0026")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e");
    }

    private static String boundedText(JsonNode node, String field, int limit) {
        String value = text(node, field);
        return value == null || value.length() > limit ? null : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Integer positiveInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.intValue() > 0
                ? node.intValue() : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
