package com.wzx.babiq.server.application.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.protocol.ApplicationCatalogMessage;
import com.wzx.babiq.server.application.protocol.ApplicationProtocol;
import com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 保存每个已认证桌面连接的过滤后动作目录和单调 catalog epoch。
 *
 * <p>payload 始终按不可信数据处理：只校验结构、权限和作用域，不解释标题、描述或值。</p>
 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public class ApplicationCatalogRegistry {

    private final ApplicationIdentityRegistry identities;
    private final Map<String, CatalogSnapshot> snapshots = new HashMap<>();

    public ApplicationCatalogRegistry(ApplicationIdentityRegistry identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    /** 首次注册当前连接的正数 catalog epoch。 */
    public synchronized CatalogSnapshot register(
            TrustedDesktopConnection connection,
            ApplicationCatalogMessage message) {
        if (snapshots.containsKey(connection.webSocketSessionId())) {
            throw new IllegalStateException("Catalog is already registered for this connection");
        }
        CatalogSnapshot candidate = validateAndBuild(connection, message);
        snapshots.put(connection.webSocketSessionId(), candidate);
        return candidate;
    }

    /** 目录更新只接受严格高于当前水位的 catalog epoch。 */
    public synchronized CatalogSnapshot update(
            TrustedDesktopConnection connection,
            ApplicationCatalogMessage message) {
        CatalogSnapshot current = requireCurrent(connection);
        CatalogSnapshot candidate = validateAndBuild(connection, message);
        if (candidate.catalogEpoch() <= current.catalogEpoch()) {
            throw new IllegalArgumentException("catalogEpoch must strictly increase");
        }
        snapshots.put(connection.webSocketSessionId(), candidate);
        return candidate;
    }

    /** 返回匹配连接的当前目录快照。 */
    public synchronized Optional<CatalogSnapshot> current(TrustedDesktopConnection connection) {
        CatalogSnapshot snapshot = snapshots.get(connection.webSocketSessionId());
        return snapshot != null && snapshot.connection().equals(connection)
                ? Optional.of(snapshot)
                : Optional.empty();
    }

    /** 连接关闭或身份变化后清空目录与 catalog epoch 水位。 */
    public synchronized void clear(TrustedDesktopConnection connection) {
        CatalogSnapshot current = snapshots.get(connection.webSocketSessionId());
        if (current != null && current.connection().equals(connection)) {
            snapshots.remove(connection.webSocketSessionId());
        }
    }

    private CatalogSnapshot validateAndBuild(
            TrustedDesktopConnection connection,
            ApplicationCatalogMessage message) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(message, "message");
        ApplicationProtocolValidator.validate(message);
        TrustedBusinessIdentity identity = requireMatchingIdentity(connection, message);
        JsonNode payload = requirePayload(message);
        validateOptionalPayloadScope(payload, identity);
        JsonNode filteredPayload = filterActions(payload, identity.permissions());
        return new CatalogSnapshot(connection, message.catalogEpoch(), filteredPayload, true);
    }

    private TrustedBusinessIdentity requireMatchingIdentity(
            TrustedDesktopConnection connection,
            ApplicationCatalogMessage message) {
        TrustedBusinessIdentity identity = identities.current(connection)
                .orElseThrow(() -> new IllegalStateException("Authenticated identity is required"));
        if (!identity.desktopInstanceId().equals(message.desktopInstanceId())
                || !identity.desktopSessionId().equals(message.desktopSessionId())
                || !identity.authSessionId().equals(message.authSessionId())
                || identity.identityEpoch() != message.identityEpoch()
                || !identity.userId().equals(message.userId())
                || !identity.tenantId().equals(message.tenantId())
                || !identity.platformId().equals(message.platformId())) {
            throw new IllegalArgumentException("Catalog identity scope does not match trusted identity");
        }
        return identity;
    }

    private JsonNode requirePayload(ApplicationCatalogMessage message) {
        JsonNode payload = message.payload();
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Catalog payload must be an object");
        }
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        if (message.payloadSize() != bytes.length) {
            throw new IllegalArgumentException("Catalog payloadSize does not match actual UTF-8 bytes");
        }
        ApplicationProtocolValidator.validateCatalogPayloadSize(bytes);
        return payload;
    }

    private JsonNode filterActions(JsonNode payload, Set<String> grantedPermissions) {
        JsonNode actions = payload.get("actions");
        if (actions == null || (!actions.isObject() && !actions.isArray())) {
            throw new IllegalArgumentException("Catalog actions must be an object or array");
        }

        ObjectNode filteredPayload = (ObjectNode) payload.deepCopy();
        if (actions.isObject()) {
            ObjectNode filtered = ApplicationProtocol.objectNode();
            actions.fields().forEachRemaining(entry -> {
                JsonNode action = validateAction(entry.getValue());
                if (isEnabledAndAllowed(action, grantedPermissions)) {
                    filtered.set(entry.getKey(), action.deepCopy());
                }
            });
            filteredPayload.set("actions", filtered);
        } else {
            ArrayNode filtered = ApplicationProtocol.objectNode().arrayNode();
            for (JsonNode candidate : actions) {
                JsonNode action = validateAction(candidate);
                if (isEnabledAndAllowed(action, grantedPermissions)) {
                    filtered.add(action.deepCopy());
                }
            }
            filteredPayload.set("actions", filtered);
        }
        return filteredPayload;
    }

    private JsonNode validateAction(JsonNode action) {
        if (action == null || !action.isObject()) {
            throw new IllegalArgumentException("Each catalog action must be an object");
        }
        JsonNode enabled = action.get("enabled");
        if (enabled != null && !enabled.isBoolean()) {
            throw new IllegalArgumentException("Action enabled must be boolean");
        }
        JsonNode requiredPermissions = action.get("requiredPermissions");
        if (requiredPermissions == null || !requiredPermissions.isArray()) {
            throw new IllegalArgumentException("Action requiredPermissions must be an array");
        }
        for (JsonNode permission : requiredPermissions) {
            if (!permission.isTextual()) {
                throw new IllegalArgumentException("Action requiredPermissions entries must be strings");
            }
        }
        return action;
    }

    private boolean isEnabledAndAllowed(JsonNode action, Set<String> grantedPermissions) {
        if (action.has("enabled") && !action.path("enabled").booleanValue()) {
            return false;
        }
        for (JsonNode permission : action.path("requiredPermissions")) {
            if (!grantedPermissions.contains(permission.textValue())) {
                return false;
            }
        }
        return true;
    }

    static void validateOptionalPayloadScope(JsonNode payload, TrustedBusinessIdentity identity) {
        requireOptionalText(payload, "desktopInstanceId", identity.desktopInstanceId());
        requireOptionalText(payload, "desktopSessionId", identity.desktopSessionId());
        requireOptionalText(payload, "authSessionId", identity.authSessionId());
        requireOptionalLong(payload, "identityEpoch", identity.identityEpoch());
        requireOptionalText(payload, "userId", identity.userId());
        requireOptionalText(payload, "tenantId", identity.tenantId());
        requireOptionalText(payload, "platformId", identity.platformId());
    }

    private static void requireOptionalText(JsonNode payload, String field, String expected) {
        JsonNode actual = payload.get(field);
        if (actual != null && (!actual.isTextual() || !expected.equals(actual.textValue()))) {
            throw new IllegalArgumentException("Payload " + field + " does not match trusted identity");
        }
    }

    private static void requireOptionalLong(JsonNode payload, String field, long expected) {
        JsonNode actual = payload.get(field);
        if (actual != null && (!actual.isIntegralNumber() || expected != actual.longValue())) {
            throw new IllegalArgumentException("Payload " + field + " does not match trusted identity");
        }
    }

    private CatalogSnapshot requireCurrent(TrustedDesktopConnection connection) {
        return current(connection)
                .orElseThrow(() -> new IllegalStateException("Catalog must be registered before update"));
    }

    /** 过滤后的目录快照；payload 仍明确标记为不可信展示数据。 */
    public record CatalogSnapshot(
            TrustedDesktopConnection connection,
            long catalogEpoch,
            JsonNode payload,
            boolean untrustedData) {
        public CatalogSnapshot {
            payload = payload == null ? null : payload.deepCopy();
        }

        @Override
        public JsonNode payload() {
            return payload == null ? null : payload.deepCopy();
        }
    }
}
